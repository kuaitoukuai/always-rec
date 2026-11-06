package com.daluo.alwaysrec

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 国产 ROM 保活引导。
 *
 * 这是「能不能连续跑 24 小时」的决定性因素——比任何代码优化都重要。
 * 小米/华为/OPPO/vivo 的省电策略会直接杀进程，必须引导用户手动放行三处：
 *   1. 自启动 / 后台启动
 *   2. 电池优化白名单
 *   3. 锁屏后保持运行（部分 ROM 单独有开关）
 */
object RomHelper {

    private val autoStartPages = listOf(
        // 小米
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // 华为
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // OPPO / 一加
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        // vivo / iQOO
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // 三星
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )

    /** 打开当前机型的自启动管理页，都不匹配时退回应用详情页。 */
    fun openAutoStart(ctx: Context): Boolean {
        for (c in autoStartPages) {
            val ok = runCatching {
                ctx.startActivity(Intent().apply {
                    component = c
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
    }

    /** 申请忽略电池优化。系统会弹一个确认框，必须用户手动同意。 */
    fun requestIgnoreBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = activity.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
        }
    }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 当前机型名，用于给用户提示具体该点哪里。 */
    fun romName(): String {
        val brand = Build.BRAND.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") -> "小米 / Redmi"
            brand.contains("huawei") || brand.contains("honor") -> "华为 / 荣耀"
            brand.contains("oppo") || brand.contains("realme") -> "OPPO / realme"
            brand.contains("vivo") || brand.contains("iqoo") -> "vivo / iQOO"
            brand.contains("samsung") -> "三星"
            else -> Build.MANUFACTURER
        }
    }

    fun hasNotificationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
