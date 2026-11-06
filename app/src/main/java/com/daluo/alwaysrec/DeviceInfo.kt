package com.daluo.alwaysrec

import android.content.Context
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * 设备身份。
 *
 * 两台手机同时往同一个网盘根目录传，光靠文件名（时间戳+4 位随机）区分不可靠，
 * 后端也没法回答"这段话是哪台手机录的"。所以给每台设备一个稳定身份：
 *
 *   id    = 机型 + android_id 的短哈希，装完就不变，用于兜底识别
 *   名字  = files/device_name.txt 的第一行（可选），可以写中文，如「备用机」
 *   目录  = 名字优先，没有名字就用 id
 *
 * 网盘布局：
 *   /录音记录/<目录>/2026-11-06/20261106_..._1234.aac
 *   /录音记录/_状态/<目录>.json          ← 每台设备一个心跳文件，互不覆盖
 *
 * 改名字不需要改代码，一条命令即可：
 *   adb shell "run-as com.daluo.alwaysrec sh -c 'echo 备用机 > files/device_name.txt'"
 */
object DeviceInfo {

    private const val PREFS = "device"
    private const val KEY_ID = "device_id"
    const val NAME_FILE = "device_name.txt"

    /** 稳定 id，ASCII，形如 22041211AC-3f2a9c。 */
    fun id(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray())
            .take(3)
            .joinToString("") { "%02x".format(it) }
        val model = android.os.Build.MODEL.filter { it.code < 128 && it.isLetterOrDigit() }
            .ifEmpty { "android" }
            .take(16)

        val id = "$model-$digest"
        p.edit().putString(KEY_ID, id).apply()
        return id
    }

    /** 自己起的名字；没设过返回 null。 */
    fun alias(ctx: Context): String? = runCatching {
        val f = File(ctx.filesDir, NAME_FILE)
        if (!f.exists()) return@runCatching null
        f.readText(Charsets.UTF_8).lineSequence().firstOrNull()?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 网盘目录名 / 心跳文件名。设了名字就用名字（允许中文），否则用 id。 */
    fun folder(ctx: Context): String {
        val a = alias(ctx)?.let { it.replace(Regex("""[/\\:*?"<>|\s]"""), "").take(24) }
        return a?.takeIf { it.isNotEmpty() } ?: id(ctx)
    }

    /** 界面上显示的名字。 */
    fun label(ctx: Context): String = alias(ctx) ?: id(ctx)

    /** 机型，用于在电脑端区分同名的两台设备。 */
    fun model(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
}
