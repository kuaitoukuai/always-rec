package com.daluo.alwaysrec

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 今日记录页：概要在上，时间线在下。
 *
 * 数据全部来自本地 transcripts/，打开即可看，不依赖网络。
 * 概要是点按钮才生成的（一次 LLM 调用），避免每次打开都花钱。
 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var store: TranscriptStore
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var day: String = dayFmt.format(Date())
    private var days: List<String> = emptyList()

    private lateinit var dateLabel: TextView
    private lateinit var summaryBox: LinearLayout
    private lateinit var timelineBox: LinearLayout
    private lateinit var statsBox: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView

    /** 「采集片段 73 段」这样的一格。 */
    private data class Metric(val label: String, val value: String, val unit: String = "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TranscriptStore(this)
        setContentView(buildUi())
        refreshDays()
        render()
    }

    override fun onResume() {
        super.onResume()
        refreshDays()
        render()
    }

    private fun refreshDays() {
        val available = store.availableDays()
        days = if (available.isEmpty()) listOf(day) else available
        if (day !in days) day = days.first()
    }

    private fun render() {
        dateLabel.text = if (day == dayFmt.format(Date())) "今天 · $day" else day

        val idx = days.indexOf(day)
        val canPrev = idx >= 0 && idx < days.size - 1
        val canNext = idx > 0
        prevBtn.alpha = if (canPrev) 1f else 0.25f
        nextBtn.alpha = if (canNext) 1f else 0.25f
        prevBtn.isClickable = canPrev
        nextBtn.isClickable = canNext

        summaryBox.removeAllViews()
        timelineBox.removeAllViews()
        renderStats()

        // 时间倒序：最新的在最上面，符合"看刚才发生了什么"的习惯
        val items = store.byDay(day).sortedByDescending { it.start }
        if (items.isEmpty()) {
            emptyHint.visibility = TextView.VISIBLE
            emptyHint.text = "这一天还没有转写内容。\n\n录完音后保持联网，App 会自动转写；\n也可以回主页点「立即上传」触发。"
            return
        }
        emptyHint.visibility = TextView.GONE

        // ---- 概要 ----
        val summary = store.loadSummary(day)
        val stale = store.isSummaryStale(day)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        if (summary.isNullOrBlank()) {
            summaryBox.addView(Button(this).apply {
                text = "生成今日概要（${items.size} 段）"
                setOnClickListener { generateSummary(items) }
            })
        } else {
            summaryBox.addView(TextView(this).apply {
                // LLM 返回的是 Markdown，这里做最小化清洗，避免满屏星号
                text = summary
                    .replace("**", "")
                    .replace("##", "")
                    .replace(Regex("(?m)^-\\s*"), "· ")
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                setLineSpacing(0f, 1.45f)
            })
            if (stale) {
                summaryBox.addView(TextView(this).apply {
                    text = "有新录音，概要可能已过时"
                    textSize = 12f
                    setTextColor(Color.parseColor("#854F0B"))
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
            }
            summaryBox.addView(TextView(this).apply {
                text = if (stale) "重新生成" else "刷新概要"
                textSize = 12f
                setTextColor(Color.parseColor("#185FA5"))
                setPadding(0, (8 * density).toInt(), 0, 0)
                setOnClickListener { generateSummary(items) }
            })
        }

        // ---- 时间线 ----
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        for (t in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
            }
            row.addView(TextView(this).apply {
                text = timeFmt.format(Date(t.start))
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                typeface = Typeface.MONOSPACE
            })
            row.addView(TextView(this).apply {
                text = t.text
                textSize = 14f
                setTextColor(Color.parseColor("#222222"))
                setLineSpacing(0f, 1.4f)
                setPadding(0, (4 * density).toInt(), 0, 0)
            })
            timelineBox.addView(row)
        }
    }

    private fun renderStats() {
        val s = Spool(this).statsForDay(day)
        val parsed = store.parsedCount(day)
        val lines = store.lineCount(day)

        statsBox.removeAllViews()
        addStatsRow(
            listOf(
                Metric("采集片段", "${s.clips}", "段"),
                Metric("录音时长", fmtDuration(s.seconds)),
                Metric("已解析", "$parsed/${s.clips}"),
            ),
        )
        addStatsRow(
            listOf(
                Metric("对话条数", "$lines", "条"),
                Metric("音频体积", fmtBytes(s.bytes)),
            ),
        )
    }

    private fun addStatsRow(items: List<Metric>) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * d).toInt())
        }
        for (i in 0 until 3) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = (8 * d).toInt()

            if (i >= items.size) {
                // 补位，保证第二行格子宽度和第一行一致
                row.addView(android.view.View(this), lp)
                continue
            }
            val m = items[i]
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F5F7FA"))
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            }
            cell.addView(TextView(this).apply {
                text = if (m.unit.isEmpty()) m.value else "${m.value} ${m.unit}"
                textSize = 17f
                setTextColor(Color.parseColor("#111111"))
            })
            cell.addView(TextView(this).apply {
                text = m.label
                textSize = 11f
                setTextColor(Color.parseColor("#8A9099"))
                setPadding(0, (2 * d).toInt(), 0, 0)
            })
            row.addView(cell, lp)
        }
        statsBox.addView(row)
    }

    private fun fmtDuration(sec: Long): String {
        if (sec <= 0) return "0 分"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}时${m}分" else "${m} 分"
    }

    private fun fmtBytes(n: Long): String =
        if (n >= 1024L * 1024L) String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
        else String.format(Locale.US, "%.0f KB", n / 1024.0)

    private fun generateSummary(items: List<Transcript>) {
        val apiKey = LocalConfig.ALIYUN_API_KEY
        if (apiKey.isBlank()) {
            summaryBox.removeAllViews()
            summaryBox.addView(TextView(this).apply {
                text = "未配置阿里云 API Key，无法生成概要"
                textSize = 13f
                setTextColor(Color.parseColor("#A32D2D"))
            })
            return
        }

        summaryBox.removeAllViews()
        summaryBox.addView(TextView(this).apply {
            text = "正在生成…"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
        })

        lifecycleScope.launch {
            val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
            // 给模型的时间线必须是正序，否则它会把因果关系理解反
            val timeline = items.sortedBy { it.start }.joinToString("\n") {
                "${timeFmt.format(Date(it.start))} ${it.text}"
            }
            val result = AliyunAsr.summarize(timeline, apiKey)
            if (result.isNullOrBlank()) {
                render()
            } else {
                store.saveSummary(day, result)
                render()
            }
        }
    }

    // ---------- UI ----------

    private fun buildUi(): ViewGroup {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // 顶栏
        // 顶栏需要避开状态栏，否则日期会被电池图标压住
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbHeight = if (sbId > 0) resources.getDimensionPixelSize(sbId) else 0

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, (12 * d).toInt() + sbHeight, pad, (8 * d).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        bar.addView(TextView(this).apply {
            text = "返回"
            textSize = 13f
            setTextColor(Color.parseColor("#185FA5"))
            setPadding(0, (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            setOnClickListener { finish() }
        })

        prevBtn = TextView(this).apply {
            text = "‹"
            textSize = 20f
            setTextColor(Color.parseColor("#555555"))
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            setOnClickListener {
                val i = days.indexOf(day)
                if (i >= 0 && i < days.size - 1) {
                    day = days[i + 1]
                    render()
                }
            }
        }
        bar.addView(prevBtn)

        dateLabel = TextView(this).apply {
            text = day
            textSize = 14f
            setTextColor(Color.parseColor("#111111"))
            gravity = android.view.Gravity.CENTER
            isSingleLine = true
        }
        bar.addView(dateLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        nextBtn = TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(Color.parseColor("#555555"))
            setPadding((8 * d).toInt(), 0, 0, 0)
            setOnClickListener {
                val i = days.indexOf(day)
                if (i > 0) {
                    day = days[i - 1]
                    render()
                }
            }
        }
        bar.addView(nextBtn)

        root.addView(bar)

        // 内容
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad * 2)
        }

        // 今日数据（采集 / 解析 / 体积）
        content.addView(TextView(this).apply {
            text = "今日数据"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
        })

        statsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statsBox)

        // 概要区
        content.addView(TextView(this).apply {
            text = "概要"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        })

        summaryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
            setPadding(pad, (12 * d).toInt(), pad, (12 * d).toInt())
        }
        content.addView(summaryBox)

        // 时间线区
        content.addView(TextView(this).apply {
            text = "时间线"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, (24 * d).toInt(), 0, (4 * d).toInt())
        })

        timelineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(timelineBox)

        emptyHint = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setLineSpacing(0f, 1.5f)
            visibility = TextView.GONE
        }
        content.addView(emptyHint)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }
}
