package com.daluo.alwaysrec

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

data class SegmentMeta(
    val id: String,
    val audio: File,
    val createdAt: Long,
    val startedAt: Long,
    val durationMs: Long,
    val bytes: Long,
    val attempts: Int,
    val uploaded: Boolean,
)

data class SpoolStats(val clips: Int, val seconds: Long, val bytes: Long)

/**
 * 本地分片仓库。核心原则：**先落盘、上传成功才标记，绝不提前删**。
 *
 * 目录约定：
 *   files/rec/pending/<id>.aac  + <id>.meta   —— 待上传
 *   files/rec/done/<id>.aac     + <id>.meta   —— 已上传，保留若干天供本地回看
 *
 * 用 Properties 而不是 JSON，是为了不引入任何序列化依赖，字段也很少。
 */
class Spool(context: Context, private val keepDays: Int = 3) {

    private val root = File(context.filesDir, "rec").apply { mkdirs() }
    private val pendingDir = File(root, "pending").apply { mkdirs() }
    private val doneDir = File(root, "done").apply { mkdirs() }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun newId(): String =
        "${stamp.format(Date())}_${(1000..9999).random()}"

    fun newAudioFile(id: String): File = File(pendingDir, "$id.aac")

    /** 分片写完时调用，落一份元数据。 */
    fun commit(id: String, audio: File, durationMs: Long, startedAt: Long) {
        val p = Properties().apply {
            setProperty("id", id)
            setProperty("createdAt", System.currentTimeMillis().toString())
            setProperty("startedAt", startedAt.toString())
            setProperty("durationMs", durationMs.toString())
            setProperty("bytes", audio.length().toString())
            setProperty("attempts", "0")
            setProperty("uploaded", "false")
        }
        File(pendingDir, "$id.meta").outputStream().use { p.store(it, null) }
    }

    fun pending(): List<SegmentMeta> =
        pendingDir.listFiles { f -> f.name.endsWith(".meta") }
            ?.mapNotNull { readMeta(it) }
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    /**
     * 抢救孤儿片段。
     *
     * 进程被杀 / 崩溃时，正在写的那一片只有 .aac，没有 .meta，
     * 而 pending() 只认 .meta —— 结果这段音频永远传不出去，也不会报错。
     * ADTS 每帧自带头，残缺文件照样能解码，所以启动时补一份 meta 把它救回来。
     *
     * 时长填 0：不重新解码，后端会用容器时长补上。
     */
    fun recoverOrphans(): Int {
        val have = pendingDir.listFiles { f -> f.name.endsWith(".meta") }
            ?.map { it.name.removeSuffix(".meta") }
            ?.toHashSet()
            ?: hashSetOf()

        var n = 0
        pendingDir.listFiles { f -> f.name.endsWith(".aac") }?.forEach { f ->
            val id = f.name.removeSuffix(".aac")
            if (id in have || f.length() < 2_048) return@forEach

            val started = runCatching {
                stamp.parse(id.substringBeforeLast('_'))?.time
            }.getOrNull() ?: f.lastModified()

            val p = Properties().apply {
                setProperty("id", id)
                setProperty("createdAt", f.lastModified().toString())
                setProperty("startedAt", started.toString())
                setProperty("durationMs", "0")
                setProperty("bytes", f.length().toString())
                setProperty("attempts", "0")
                setProperty("uploaded", "false")
                setProperty("recovered", "true")
            }
            runCatching {
                File(pendingDir, "$id.meta").outputStream().use { p.store(it, null) }
                n++
            }
        }
        return n
    }

    fun markAttempt(meta: SegmentMeta, success: Boolean) {
        val metaFile = File(pendingDir, "${meta.id}.meta")
        if (!metaFile.exists()) return
        val p = Properties().apply { metaFile.inputStream().use { load(it) } }
        p.setProperty("attempts", (p.getProperty("attempts")?.toIntOrNull() ?: 0).plus(1).toString())
        if (success) p.setProperty("uploaded", "true")
        metaFile.outputStream().use { p.store(it, null) }
        if (success && meta.audio.exists()) {
            meta.audio.renameTo(File(doneDir, meta.audio.name))
            metaFile.renameTo(File(doneDir, metaFile.name))
        }
    }

    /**
     * 清理已完成且超过保留期的分片。
     * 这是唯一会删音频的地方，且只删「已确认上传成功」的。
     */
    fun pruneUploaded() {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L
        doneDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".meta")) {
                val created = readProps(f)?.getProperty("createdAt")?.toLongOrNull() ?: return@forEach
                if (created < cutoff) {
                    val id = f.name.removeSuffix(".meta")
                    File(doneDir, "$id.aac").delete()
                    f.delete()
                }
            }
        }
    }

    fun stats(): Pair<Int, Long> {
        val files = pendingDir.listFiles { f -> f.name.endsWith(".aac") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    /**
     * 某一天的采集统计。day 格式 yyyy-MM-dd。
     *
     * 时长直接从 .meta 里的 durationMs 累加，不重新解码音频 —— 录的时候已经算好了。
     */
    fun statsForDay(day: String): SpoolStats {
        val prefix = day.replace("-", "")
        var clips = 0
        var ms = 0L
        var bytes = 0L
        for (dir in listOf(pendingDir, doneDir)) {
            dir.listFiles { f -> f.name.endsWith(".aac") && f.name.startsWith(prefix) }
                ?.forEach { f ->
                    clips++
                    bytes += f.length()
                    ms += durationOf(f)
                }
        }
        return SpoolStats(clips, ms / 1000, bytes)
    }

    private fun durationOf(audio: File): Long {
        val meta = File(audio.parentFile, "${audio.nameWithoutExtension}.meta")
        if (!meta.exists()) return 0L
        return runCatching {
            meta.readLines()
                .firstOrNull { it.startsWith("durationMs=") }
                ?.substringAfter("=")
                ?.trim()
                ?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    private fun readProps(f: File): Properties? = runCatching {
        Properties().apply { f.inputStream().use { load(it) } }
    }.getOrNull()

    private fun readMeta(metaFile: File): SegmentMeta? {
        val p = readProps(metaFile) ?: return null
        val id = p.getProperty("id") ?: return null
        val audio = File(metaFile.parentFile, "$id.aac")
        if (!audio.exists()) return null
        return SegmentMeta(
            id = id,
            audio = audio,
            createdAt = p.getProperty("createdAt")?.toLongOrNull() ?: 0L,
            startedAt = p.getProperty("startedAt")?.toLongOrNull() ?: 0L,
            durationMs = p.getProperty("durationMs")?.toLongOrNull() ?: 0L,
            bytes = p.getProperty("bytes")?.toLongOrNull() ?: 0L,
            attempts = p.getProperty("attempts")?.toIntOrNull() ?: 0,
            uploaded = p.getProperty("uploaded")?.toBoolean() ?: false,
        )
    }
}
