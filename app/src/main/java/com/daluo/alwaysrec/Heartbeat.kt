package com.daluo.alwaysrec

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 心跳上报。
 *
 * 目的很单纯：让你在电脑上就能判断手机上的录音服务还活着没有。
 *
 * 判断逻辑——服务在跑就每 10 分钟写一次，服务被杀心跳自然就停了。
 * 电脑端只要看"最后一次心跳距今多久"，超过 25 分钟就说明出事了。
 * 这比在手机上看通知栏可靠，因为有些 ROM 杀进程前不会告诉你。
 *
 * 同时写本地和网盘：本地用于 App 内展示，网盘用于远程查看。
 */
object Heartbeat {

    private const val DIR = "/录音记录/_状态"
    private const val NAME = "device.json"

    data class Snapshot(
        val ts: Long,
        val recording: Boolean,
        val pending: Int,
        val battery: Int,
        val charging: Boolean,
    )

    /** 采集一次并上报。 */
    suspend fun beat(ctx: Context, recording: Boolean, pending: Int): Snapshot =
        withContext(Dispatchers.IO) {
            val snap = Snapshot(
                ts = System.currentTimeMillis(),
                recording = recording,
                pending = pending,
                battery = batteryPercent(ctx),
                charging = isCharging(ctx),
            )

            val json = JSONObject().apply {
                put("ts", snap.ts)
                put(
                    "time",
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(snap.ts)),
                )
                put("recording", snap.recording)
                put("pending", snap.pending)
                put("battery", snap.battery)
                put("charging", snap.charging)
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("app", "AlwaysRec")
            }

            // 本地留一份，App 内可以直接读
            runCatching {
                File(ctx.filesDir, "status.json").writeText(json.toString(), Charsets.UTF_8)
            }

            // 上传网盘，电脑端靠这个判断
            runCatching {
                Uploader.current(ctx)?.uploadStatus(json.toString().toByteArray(Charsets.UTF_8))
            }

            snap
        }

    /** 读本地最近一次心跳，没跑过返回 null。 */
    fun lastLocal(ctx: Context): Snapshot? = runCatching {
        val f = File(ctx.filesDir, "status.json")
        if (!f.exists()) return@runCatching null
        val j = JSONObject(f.readText(Charsets.UTF_8))
        Snapshot(
            ts = j.optLong("ts"),
            recording = j.optBoolean("recording"),
            pending = j.optInt("pending"),
            battery = j.optInt("battery"),
            charging = j.optBoolean("charging"),
        )
    }.getOrNull()

    private fun batteryPercent(ctx: Context): Int = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun isCharging(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    /** Build 在 object 里不能直接用，这里偷个懒取静态值。 */
    private object Build {
        val MANUFACTURER: String get() = android.os.Build.MANUFACTURER
        val MODEL: String get() = android.os.Build.MODEL
    }
}
