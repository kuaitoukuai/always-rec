package com.daluo.alwaysrec

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Transcript(
    val clipId: String,
    val start: Long,
    val durationMs: Long,
    val text: String,
)

/**
 * 转写结果的本地仓库。
 *
 * 一个片段一份 <id>.txt，存在即代表已转写 —— 用它当"已处理"标记，
 * 不用再维护独立的状态文件。即使传上网盘失败，文字也还在手机上。
 */
class TranscriptStore(context: Context) {

    private val dir = File(context.filesDir, "transcripts").apply { mkdirs() }
    private val summaryDir = File(context.filesDir, "summary").apply { mkdirs() }

    private val idTime = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun exists(clipId: String): Boolean = File(dir, "$clipId.txt").exists()

    fun pendingIds(clipIds: Collection<String>): List<String> = clipIds.filterNot { exists(it) }

    fun save(clipId: String, text: String) {
        runCatching { File(dir, "$clipId.txt").writeText(text, Charsets.UTF_8) }
    }

    fun load(clipId: String): Transcript? {
        val f = File(dir, "$clipId.txt")
        if (!f.exists()) return null
        val start = parseStart(clipId) ?: return null
        return Transcript(clipId, start, 0, f.readText(Charsets.UTF_8))
    }

    /** 某一天的全部转写，按时间升序。day 格式 yyyy-MM-dd */
    fun byDay(day: String): List<Transcript> =
        dir.listFiles { f -> f.name.endsWith(".txt") }
            ?.mapNotNull { f ->
                val id = f.name.removeSuffix(".txt")
                val start = parseStart(id) ?: return@mapNotNull null
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start))
                if (d != day) return@mapNotNull null
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                if (text.isBlank()) null else Transcript(id, start, 0, text)
            }
            ?.sortedBy { it.start }
            ?: emptyList()

    fun availableDays(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".txt") }
            ?.mapNotNull { parseStart(it.name.removeSuffix(".txt")) }
            ?.map { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) }
            ?.distinct()
            ?.sortedDescending()
            ?: emptyList()

    // ---- 摘要 ----

    fun saveSummary(day: String, text: String) {
        runCatching { File(summaryDir, "$day.md").writeText(text, Charsets.UTF_8) }
    }

    fun loadSummary(day: String): String? =
        File(summaryDir, "$day.md").takeIf { it.exists() }?.readText(Charsets.UTF_8)

    fun clearSummary(day: String) {
        File(summaryDir, "$day.md").delete()
    }

    /** 摘要是否已落后于转写内容（有新的转写但摘要没更新）。 */
    fun isSummaryStale(day: String): Boolean {
        val s = File(summaryDir, "$day.md")
        if (!s.exists()) return true
        val prefix = day.replace("-", "")
        val latest = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".txt") }
            ?.maxOfOrNull { it.lastModified() } ?: 0L
        return latest > s.lastModified()
    }

    /** 某天已转写的片段数（含空结果，空也是一种"确认没人说话"的结论）。 */
    fun parsedCount(day: String): Int =
        dir.listFiles { f -> f.name.startsWith(day.replace("-", "")) && f.name.endsWith(".txt") }
            ?.size ?: 0

    /** 某天的对话条数：按句号问号分号切句，比按片段数更能反映信息量。 */
    fun lineCount(day: String): Int = byDay(day).sumOf { splitSentences(it.text).size }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("[。！？；\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun parseStart(clipId: String): Long? {
        val m = Regex("(\\d{8})_(\\d{6})").find(clipId) ?: return null
        return runCatching { idTime.parse(m.value)?.time }.getOrNull()
    }
}
