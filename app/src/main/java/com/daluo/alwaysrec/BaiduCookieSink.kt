package com.daluo.alwaysrec

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 百度网盘上传 —— 网页版接口 + BDUSS cookie。
 *
 * 为什么不用开放平台 OAuth：开放平台要 appKey/appSecret + 授权码流程，
 * 个人开发者审核麻烦；网页版接口（app_id=250528）只要 BDUSS + bdstoken，
 * 跟 baidu_upload.py 那套完全一致，后端和 App 可以共用同一份 cookie。
 *
 * 目录必须是普通目录（如 /录音记录）。/apps/ 是「我的应用数据」，
 * 网盘客户端默认不显示，用户会找不到文件。
 */
class BaiduCookieSink(
    /** 形如 "BDUSS=xxx; STOKEN=yyy" */
    private val cookie: String,
    private val bdstoken: String,
    /** 网盘目标根目录，如 /录音记录 */
    private val remoteDir: String,
    /** 设备名，用于隔离不同手机的数据；留空则沿用旧布局 */
    private val device: String = "",
) : UploadSink {

    /** 音频与转写的落地目录：/根/设备/日期 */
    private fun dayDir(ts: Long): String {
        val base = remoteDir.trimEnd('/')
        val day = dayFmt.format(Date(ts))
        return if (device.isBlank()) "$base/$day" else "$base/$device/$day"
    }

    /** 心跳目录：/根/_状态/设备.json，每台设备一个文件 */
    private fun statusName(): String =
        if (device.isBlank()) "device.json" else "$device.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val ua =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override suspend fun upload(meta: SegmentMeta): Boolean = withContext(Dispatchers.IO) {
        uploadFile(meta.audio, dayDir(meta.startedAt), "${meta.id}.aac")
    }

    /** 把转写文本一起传上去，后端就不用重复调 ASR 了。 */
    override val supportsCompanion: Boolean get() = true

    override suspend fun uploadCompanion(
        meta: SegmentMeta,
        name: String,
        bytes: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = dayDir(meta.startedAt)
        // 用独立临时文件：补传时 meta.audio 本身就是那份待补传的 txt，
        // 直接写它会把自己删掉
        val tmp = File.createTempFile("companion", ".txt")
        try {
            tmp.writeBytes(bytes)
            uploadFile(tmp, dir, name)
        } catch (t: Throwable) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** 心跳按设备分开存，电脑端扫 _状态 目录就能列出所有在线设备。 */
    override suspend fun uploadStatus(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("status", ".json")
        try {
            tmp.writeBytes(bytes)
            uploadFile(tmp, "$remoteDir/_状态", statusName())
        } catch (t: Throwable) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** 真正的上传实现，aac / txt 共用。 */
    private fun uploadFile(file: File, dir: String, remoteName: String): Boolean {
        return runCatching {
            val remotePath = "$dir/$remoteName"
            ensureDir(dir)

            val size = file.length()
            val blocks = JSONArray().put(md5Of(file)).toString()

            val pre = postForm(
                "https://pan.baidu.com/api/precreate?${query(extra = "logid=${logid()}")}",
                mapOf(
                    "path" to remotePath,
                    "size" to size.toString(),
                    "isdir" to "0",
                    "autoinit" to "1",
                    "block_list" to blocks,
                    "rtype" to "1",
                ),
            ) ?: return false

            val errno = pre.optInt("errno", -1)
            if (errno != 0 && errno != -8) return false

            val uploadId = pre.optString("uploadid")
            if (uploadId.isEmpty()) return true // 秒传命中

            if (!uploadPart(remotePath, uploadId, file)) return false

            val created = postForm(
                "https://pan.baidu.com/api/create?" +
                    query(extra = "isdir=0&rtype=1&logid=${logid()}"),
                mapOf(
                    "path" to remotePath,
                    "size" to size.toString(),
                    "uploadid" to uploadId,
                    "block_list" to blocks,
                    "local_mtime" to (System.currentTimeMillis() / 1000).toString(),
                ),
            ) ?: return false

            created.optInt("errno", -1) == 0
        }.getOrDefault(false)
    }

    // ---------- 内部 ----------

    private fun headers(referer: String = "https://pan.baidu.com/disk/home"): Headers =
        Headers.Builder()
            .add("User-Agent", ua)
            .add("Referer", referer)
            .add("Cookie", cookie)
            .build()

    private fun query(extra: String = ""): String {
        val base = "app_id=250528&channel=chunlei&web=1&clienttype=0&bdstoken=$bdstoken"
        return if (extra.isEmpty()) base else "$base&$extra"
    }

    private fun logid(): String {
        val raw = "${System.currentTimeMillis()}.${android.os.Process.myPid()}"
        return Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    private fun bduss(): String =
        cookie.substringAfter("BDUSS=", "").substringBefore(";").trim()

    private fun dirExists(dir: String): Boolean {
        val url = "https://pan.baidu.com/api/list?" +
            query("dir=${enc(dir)}&order=time&start=0&limit=1&logid=${logid()}")
        val req = Request.Builder().url(url).headers(headers()).get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val j = JSONObject(resp.body?.string() ?: "{}")
                j.optInt("errno", -1) == 0
            }
        }.getOrDefault(false)
    }

    private fun ensureDir(dir: String) {
        var cur = ""
        for (part in dir.trim('/').split("/").filter { it.isNotEmpty() }) {
            cur += "/$part"
            if (!dirExists(cur)) {
                postForm(
                    "https://pan.baidu.com/api/create?${query("isdir=1&rtype=1&logid=${logid()}")}",
                    mapOf(
                        "path" to cur,
                        "isdir" to "1",
                        "block_list" to "[]",
                        "local_mtime" to (System.currentTimeMillis() / 1000).toString(),
                    ),
                )
            }
        }
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject? {
        val form = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val req = Request.Builder()
            .url(url)
            .headers(headers())
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@use null
                JSONObject(text)
            }
        }.getOrNull()
    }

    private fun uploadPart(remotePath: String, uploadId: String, file: File): Boolean {
        val url = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2" +
            "?method=upload&app_id=250528&channel=chunlei&clienttype=0&web=1" +
            "&BDUSS=${enc(bduss())}" +
            "&path=${enc(remotePath)}&uploadid=${enc(uploadId)}" +
            "&uploadsign=0&partseq=0"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "blob",
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()

        val req = Request.Builder().url(url).headers(headers()).post(body).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                text.contains("\"md5\"") || JSONObject(text).optInt("errno", -1) == 0
            }
        }.getOrDefault(false)
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
