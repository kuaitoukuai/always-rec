package com.daluo.alwaysrec

/**
 * 配置入口。
 *
 * ⚠️ 真实凭据不写在这个文件里 —— 开源仓库中的这几个值都是空的。
 *    本机的值放在根目录 local.properties（已被 .gitignore 忽略），
 *    构建时由 app/build.gradle.kts 注入成 BuildConfig 常量。
 *
 * 留空不会崩，只是对应功能不可用：
 *   没有网盘凭据 → 只录音，不上传
 *   没有 dashscope key → 不转写，不生成概要
 */
object LocalConfig {

    // ---- 自建 HTTP（可留空）----
    const val HTTP_BASE_URL = ""
    const val HTTP_TOKEN = ""

    // ---- 百度网盘（网页版接口，无需 appKey）----
    val BAIDU_BDUSS: String = BuildConfig.BAIDU_BDUSS
    val BAIDU_STOKEN: String = BuildConfig.BAIDU_STOKEN
    val BAIDU_BDSTOKEN: String = BuildConfig.BAIDU_BDSTOKEN

    /**
     * 网盘根目录。每台设备会在它下面建自己的子目录：
     *   /录音记录/<设备名>/2026-11-06/xxx.aac
     * 必须是普通目录；不要用 /apps/（那是「我的应用数据」，客户端默认不显示）。
     */
    const val BAIDU_DIR = "/录音记录"

    // ---- 阿里云百炼（语音转写 + 摘要）----
    /** 同一个 key 既用于 qwen3-asr-flash 转写，也用于 qwen-plus 摘要 */
    val ALIYUN_API_KEY: String = BuildConfig.DASHSCOPE_KEY

    /** "none" / "http" / "baidu"。有网盘凭据就自动走网盘。 */
    val UPLOAD_MODE: String = when {
        BAIDU_BDUSS.isNotBlank() -> "baidu"
        HTTP_BASE_URL.isNotBlank() -> "http"
        else -> "none"
    }
}
