package com.daluo.alwaysrec

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 上传目标抽象。
 *
 * 这一层的意义：录音代码完全不知道数据最终去哪，换存储不用改采集。
 * 你已有的百度网盘上传逻辑直接塞进 BaiduPanSink 即可，其余不用动。
 */
interface UploadSink {
    /** 返回 true 表示服务端已确认收到，可以安全标记为已上传。 */
    suspend fun upload(meta: SegmentMeta): Boolean

    /**
     * 这个 sink 是否支持上传伴随文本。
     * 不支持时不要排队重试 —— 否则每次运行都会攒下一堆永远传不出去的文本。
     */
    val supportsCompanion: Boolean get() = false

    /**
     * 上传伴随文本（转写结果）。默认不支持。
     * 传上去的目的是让后端复用现成文本，不必重复调 ASR 再花一次钱。
     */
    suspend fun uploadCompanion(meta: SegmentMeta, name: String, bytes: ByteArray): Boolean = false

    /** 上传心跳状态，供电脑端判断设备是否还活着。 */
    suspend fun uploadStatus(bytes: ByteArray): Boolean = false
}

/** 远程文件命名：按日期分目录，方便后端按天批处理。 */
fun SegmentMeta.remoteName(): String {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date(startedAt))
    return "$d/${id}.aac"
}

/** 极简 HTTP PUT，用来先在自建服务 / NAS 上把链路跑通。 */
class HttpPutSink(
    private val baseUrl: String,
    private val token: String = "",
) : UploadSink {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun upload(meta: SegmentMeta): Boolean = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/" + meta.remoteName()
        val req = Request.Builder()
            .url(url)
            .put(meta.audio.asRequestBody("audio/aac".toMediaType()))
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}

/**
 * 百度网盘上传。走开放平台标准三步骤：precreate → superfile2 → create。
 *
 * 需要你填的：
 *  - APP_KEY / APP_SECRET：网盘开放平台应用信息
 *  - access_token / refresh_token：首次授权后拿到，存 SharedPreferences
 *
 * 分片规则：网盘以 4MB 为一块。本项目单片最长 5 分钟、32kbps，约 1.2MB，
 * 永远只有一块，所以 block_list 就是整文件 MD5 的单元素数组。
 * 若你后续调大 maxSegmentMs 超过约 16 分钟，这里需要改成分块算 MD5。
 */
class BaiduPanSink(
    private val ctx: Context,
    private val appKey: String,
    private val appSecret: String,
    /** 网盘内的目标目录，必须以 /apps/你的应用目录 开头 */
    private val remoteDir: String,
) : UploadSink {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val prefs = ctx.getSharedPreferences("baidu_pan", Context.MODE_PRIVATE)

    override suspend fun upload(meta: SegmentMeta): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext false
        val path = remoteDir.trimEnd('/') + "/" + meta.remoteName()
        val size = meta.audio.length()
        val md5 = md5Of(meta.audio)
        val blocks = JSONArray().put(md5).toString()

        // 1. 预创建
        val pre = postForm(
            "https://pan.baidu.com/rest/2.0/xpan/file?method=precreate&access_token=$token",
            mapOf(
                "path" to path,
                "size" to size.toString(),
                "isdir" to "0",
                "autoinit" to "1",
                "rtype" to "3",
                "block_list" to blocks,
            ),
        ) ?: return@withContext false

        val errno = pre.optInt("errno", -1)
        if (errno != 0) return@withContext false

        // return_type=2 表示秒传命中，文件已在网盘上
        if (pre.optInt("return_type", 0) == 2) return@withContext true

        val uploadId = pre.optString("uploadid")
        if (uploadId.isEmpty()) return@withContext false

        // 2. 上传分片（单块）
        val partOk = uploadPart(token, path, uploadId, meta.audio)
        if (!partOk) return@withContext false

        // 3. 创建文件
        val created = postForm(
            "https://pan.baidu.com/rest/2.0/xpan/file?method=create&access_token=$token",
            mapOf(
                "path" to path,
                "size" to size.toString(),
                "isdir" to "0",
                "rtype" to "3",
                "uploadid" to uploadId,
                "block_list" to blocks,
            ),
        ) ?: return@withContext false

        created.optInt("errno", -1) == 0
    }

    private fun uploadPart(token: String, path: String, uploadId: String, file: File): Boolean {
        val enc = URLEncoder.encode(path, "UTF-8")
        val url = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2" +
            "?method=upload&access_token=$token&type=tmpfile" +
            "&path=$enc&uploadid=$uploadId&partseq=0"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val req = Request.Builder().url(url).post(body).build()
        return runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject? {
        val form = fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val req = Request.Builder()
            .url(url)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@use null
                JSONObject(text)
            }
        }.getOrNull()
    }

    /** access_token 有效期 30 天，过期用 refresh_token 换新的。 */
    private fun accessToken(): String? {
        val now = System.currentTimeMillis()
        val token = prefs.getString("access_token", null)
        val expireAt = prefs.getLong("expire_at", 0L)
        if (!token.isNullOrEmpty() && now < expireAt - 86_400_000L) return token

        val refresh = prefs.getString("refresh_token", null) ?: return token
        val url = "https://openapi.baidu.com/oauth/2.0/token?grant_type=refresh_token" +
            "&refresh_token=$refresh&client_id=$appKey&client_secret=$appSecret"
        val req = Request.Builder().url(url).post("".toRequestBody(null)).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: return@use null)
                val newToken = json.optString("access_token").takeIf { it.isNotEmpty() } ?: return@use null
                prefs.edit()
                    .putString("access_token", newToken)
                    .putString("refresh_token", json.optString("refresh_token", refresh))
                    .putLong("expire_at", now + json.optLong("expires_in", 2_592_000L) * 1000L)
                    .apply()
                newToken
            }
        }.getOrNull()
    }

    private fun md5Of(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * 上传调度。
 *
 * 省电的关键在这里，而不是录音本身：
 *  - 只在 WiFi 下传（UNMETERED），避免蜂窝网络唤醒和高额流量
 *  - 15 分钟一次，WorkManager 会自动合并批次
 *  - 断网 / 失败自动退避重试，永远不丢本地文件
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val UNIQUE_NAME = "always_rec_upload"
        private const val WIFI_UNIQUE = "always_rec_upload_wifi"
        private const val MAX_ATTEMPTS = 20
        private const val TXT_RETRY = "txtretry"

        private fun retryDir(ctx: Context): File =
            File(ctx.filesDir, TXT_RETRY).apply { mkdirs() }

        /** 文本上传失败的兜底：音频已经进 done 了，只能把文本单独存一份等下次补传。 */
        private fun queueCompanion(ctx: Context, id: String, text: String) {
            runCatching {
                File(retryDir(ctx), "$id.txt").writeText(text, Charsets.UTF_8)
            }
        }

        private suspend fun retryCompanions(ctx: Context, sink: UploadSink) {
            if (!sink.supportsCompanion) return
            val files = retryDir(ctx).listFiles { f -> f.name.endsWith(".txt") } ?: return
            for (f in files) {
                val id = f.name.removeSuffix(".txt")
                val started = startedAtOf(id) ?: continue
                val dummy = SegmentMeta(
                    id = id,
                    audio = f,
                    createdAt = started,
                    startedAt = started,
                    durationMs = 0,
                    bytes = f.length(),
                    attempts = 0,
                    uploaded = true,
                )
                val ok = runCatching {
                    sink.uploadCompanion(dummy, f.name, f.readBytes())
                }.getOrDefault(false)
                if (ok) f.delete()
            }
        }

        /** 片段 id 形如 20261106_114547_1466，用于还原它属于哪一天。 */
        private fun startedAtOf(id: String): Long? = runCatching {
            val p = id.split("_")
            if (p.size < 2) return@runCatching null
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(p[0] + p[1])?.time
        }.getOrNull()

        fun schedule(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
        }

        /**
         * 立即上传一次。用户主动点的场景不限制 WiFi —— 点了就是要传。
         * 周期任务仍然限定 UNMETERED，避免后台偷跑流量。
         */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }

        /**
         * 连上 WiFi 时触发。用 KEEP 去重，否则网络状态回调一抖动就会堆出一串任务。
         */
        fun runOnWifi(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WIFI_UNIQUE, ExistingWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val spool = Spool(applicationContext)
        val store = TranscriptStore(applicationContext)
        val sink: UploadSink = Uploader.current(applicationContext) ?: return Result.success()
        val apiKey = LocalConfig.ALIYUN_API_KEY

        return WorkLock.upload.withLock {
            // 上次音频传成功、文本没传成功的，先补传（评审报告 P1-2）
            retryCompanions(applicationContext, sink)

            var failed = 0
            for (meta in spool.pending().take(50)) {
                if (meta.attempts >= MAX_ATTEMPTS) continue

                // 上传前补齐转写：保证云端 aac 和 txt 成对，后端不用再单独跑 ASR
                // 用 null 判断而不是空白判断 —— 空字符串代表"已经识别过，确认没人说话"，
                // 不该再调一次 ASR
                var text = runCatching { store.load(meta.id)?.text }.getOrNull()
                if (text == null && apiKey.isNotBlank()) {
                    // 和转写 Worker 互斥，避免同一段被两次 ASR（评审报告 P1-6）
                    val r = WorkLock.asr.withLock { AliyunAsr.transcribe(meta.audio, apiKey) }
                    if (r.text != null) {
                        store.save(meta.id, r.text)
                        text = r.text
                    } else if (r.ok) {
                        store.save(meta.id, "")
                        text = ""
                    }
                }

                val ok = runCatching { sink.upload(meta) }.getOrDefault(false)
                if (ok && text != null && sink.supportsCompanion) {
                    // 空结果也传一个占位符：后端据此知道"这段确实没人说话"，
                    // 而不是"手机没传文本"，从而不再重复识别（评审报告 P1-1）
                    val payload = if (text.isEmpty()) " " else text
                    val sent = runCatching {
                        sink.uploadCompanion(meta, "${meta.id}.txt", payload.toByteArray(Charsets.UTF_8))
                    }.getOrDefault(false)
                    // 文本没传上去就排队，下次运行补传；不能默默当成功
                    if (!sent) queueCompanion(applicationContext, meta.id, payload)
                }
                spool.markAttempt(meta, ok)
                if (!ok) failed++
            }
            spool.pruneUploaded()
            if (failed == 0) Result.success() else Result.retry()
        }
    }
}

/** 上传目标的选择逻辑，配置见 LocalConfig.kt。 */
object Uploader {
    fun current(ctx: Context): UploadSink? {
        return when (LocalConfig.UPLOAD_MODE) {
            "http" -> HttpPutSink(
                baseUrl = LocalConfig.HTTP_BASE_URL,
                token = LocalConfig.HTTP_TOKEN,
            )

            "baidu" -> {
                if (LocalConfig.BAIDU_BDUSS.isBlank()) return null
                var cookie = "BDUSS=${LocalConfig.BAIDU_BDUSS}"
                if (LocalConfig.BAIDU_STOKEN.isNotBlank()) {
                    cookie += "; STOKEN=${LocalConfig.BAIDU_STOKEN}"
                }
                BaiduCookieSink(
                    cookie = cookie,
                    bdstoken = LocalConfig.BAIDU_BDSTOKEN,
                    remoteDir = LocalConfig.BAIDU_DIR,
                    device = DeviceInfo.folder(ctx),
                )
            }

            else -> null
        }
    }
}
