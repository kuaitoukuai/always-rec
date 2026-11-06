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
import java.util.concurrent.TimeUnit

/**
 * 后台自动转写。
 *
 * 触发时机：每录完一个片段（SegmentEngine 关闭片段时）就排一次。
 * 用 KEEP 策略合并，不会因为连续录很多段而排出一堆任务。
 *
 * 与上传解耦：转写只要联网就能做，不必等 WiFi；上传走另一条 Worker。
 * 文字落地在本地 transcripts/ 目录，即使上传失败也不会丢。
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
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val apiKey = LocalConfig.ALIYUN_API_KEY
        if (apiKey.isBlank()) return Result.success()

        val spool = Spool(applicationContext)
        val store = TranscriptStore(applicationContext)
        val pending = spool.pending()
        val todo = pending.filterNot { store.exists(it.id) }
        if (todo.isEmpty()) return Result.success()

        var failed = 0
        for (meta in todo) {
            val r = AliyunAsr.transcribe(meta.audio, apiKey)
            when {
                r.text != null -> store.save(meta.id, r.text)
                // 调用成功但没内容：写个空文件当作"已处理"标记，
                // 否则统计里会被误算成"没解析"
                r.ok -> store.save(meta.id, "")
                else -> failed++
            }
        }

        // 转写完立刻推动上传，否则用户要等 15 分钟的周期任务才能在网上看到
        runCatching { UploadWorker.runOnWifi(applicationContext) }

        return if (failed == 0) Result.success() else Result.retry()
    }
}
