package com.daluo.alwaysrec

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 后台自动转写。
 *
 * 触发时机：每录完一个片段（SegmentEngine 关闭片段时）就排一次。
 * 策略用 APPEND_OR_REPLACE 而不是 KEEP —— KEEP 会在旧任务运行时丢掉新请求，
 * 新片段就得等下一次事件才转写。
 *
 * 与上传解耦：转写只要联网就能做，不必等 WiFi；上传走另一条 Worker。
 * 文字落地在本地 transcripts/ 目录，即使上传失败也不会丢。
 * 与上传 Worker 通过 WorkLock.asr 互斥，避免同一段被两次 ASR。
 */
class TranscribeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val UNIQUE = "always_rec_transcribe"

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }

    override suspend fun doWork(): Result {
        val apiKey = LocalConfig.ALIYUN_API_KEY
        if (apiKey.isBlank()) return Result.success()

        val spool = Spool(applicationContext)
        val store = TranscriptStore(applicationContext)

        var failed = 0
        var done = 0

        WorkLock.asr.withLock {
            // 跑两遍：第一遍执行期间可能又有片段封口，
            // 只取一次快照会漏掉它们（评审报告 P1-6）。
            repeat(2) { pass ->
                val todo = spool.pending().filterNot { store.exists(it.id) }
                if (todo.isEmpty()) return@withLock

                for (meta in todo) {
                    val r = AliyunAsr.transcribe(meta.audio, apiKey)
                    when {
                        r.text != null -> {
                            store.save(meta.id, r.text)
                            done++
                        }
                        // 调用成功但没内容：写个空文件当作"已处理"标记，
                        // 否则统计里会被误算成"没解析"
                        r.ok -> {
                            store.save(meta.id, "")
                            done++
                        }
                        else -> failed++
                    }
                }
            }
        }

        // 转写完立刻推动上传，否则用户要等 15 分钟的周期任务才能在网上看到
        if (done > 0) runCatching { UploadWorker.runOnWifi(applicationContext) }

        return if (failed == 0) Result.success() else Result.retry()
    }
}
