package com.daluo.alwaysrec

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var queueView: TextView
    private lateinit var mainBtn: Button

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderService.state.collect { render(it) }
            }
        }

        askPermissions()
        UploadWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refreshQueue()
        render(RecorderService.state.value)
    }

    private fun askPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(list.toTypedArray())
    }

    private fun render(state: RecordingState) {
        mainBtn.text = if (RecorderService.running) "停止录音" else "开始录音"
        statusView.text = when {
            !RecorderService.running -> "已停止"
            state.active -> "正在录制片段 ${state.segmentId ?: ""}"
            else -> "监听中（等待人声触发）"
        }
        refreshQueue()
    }

    private fun refreshQueue() {
        val (count, bytes) = Spool(this).stats()
        queueView.text = "待上传 $count 片 · ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }

    private fun buildUi(): ViewGroup {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "常驻录音"
            textSize = 24f
            setTextColor(Color.parseColor("#111111"))
        })

        statusView = TextView(this).apply {
            text = "已停止"
            textSize = 15f
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        queueView = TextView(this).apply {
            text = "待上传 0 片 · 0.0 MB"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, pad)
        }
        root.addView(queueView)

        mainBtn = Button(this).apply {
            text = "开始录音"
            setOnClickListener {
                if (RecorderService.running) {
                    RecorderService.stop(this@MainActivity)
                } else {
                    if (RomHelper.isIgnoringBatteryOptimizations(this@MainActivity)) {
                        RecorderService.start(this@MainActivity)
                    } else {
                        RomHelper.requestIgnoreBatteryOptimization(this@MainActivity)
                        RecorderService.start(this@MainActivity)
                    }
                }
                mainBtn.postDelayed({ render(RecorderService.state.value) }, 400)
            }
        }
        root.addView(mainBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val romTip = TextView(this).apply {
            text = "当前机型：${RomHelper.romName()}\n" +
                "国产 ROM 必须手动放行，否则录音会在几十分钟后被系统杀掉：\n" +
                "① 允许自启动 / 后台运行\n" +
                "② 电池优化选「无限制」\n" +
                "③ 最近任务里给本应用加锁"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(romTip)

        root.addView(Button(this).apply {
            text = "打开自启动设置"
            setOnClickListener { RomHelper.openAutoStart(this@MainActivity) }
        })

        root.addView(Button(this).apply {
            text = "关闭电池优化"
            setOnClickListener { RomHelper.requestIgnoreBatteryOptimization(this@MainActivity) }
        })

        root.addView(Button(this).apply {
            text = "立即上传"
            setOnClickListener {
                UploadWorker.runNow(this@MainActivity)
                mainBtn.postDelayed({ refreshQueue() }, 1500)
            }
        })

        root.addView(Button(this).apply {
            text = "查看今日记录"
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@MainActivity, SummaryActivity::class.java),
                )
            }
        })

        val note = TextView(this).apply {
            text = "提示：重启手机后需手动打开本页重新开始录音。\n" +
                "Android 14+ 禁止开机自启麦克风类前台服务，这是系统限制。"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, pad, 0, 0)
            gravity = Gravity.START
        }
        root.addView(note)

        return ScrollView(this).apply { addView(root) }
    }
}
