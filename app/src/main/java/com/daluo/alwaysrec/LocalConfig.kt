package com.daluo.alwaysrec

/**
 * 本地配置。
 *
 * ⚠️ 这个文件里的值全部需要你自己填。默认留空，填错不会崩，只是对应功能不可用。
 * ⚠️ 填完**不要提交到 Git**，这个文件已列入 .gitignore 的例外清单之外——
 *    如果你 fork 后要提交，请用 `git update-index --skip-worktree` 保护它。
 *
 * 个人自用直接改这里重新编译，不提供设置界面。
 */
object LocalConfig {

    /** "none"（只录不上传） / "http"（自建服务） / "baidu"（百度网盘） */
    const val UPLOAD_MODE = "none"

    // ---- 自建 HTTP ----
    const val HTTP_BASE_URL = ""
    const val HTTP_TOKEN = ""

    // ---- 百度网盘（网页版接口，无需 appKey）----
    /**
     * 三个值都来自浏览器登录 pan.baidu.com 后的 cookie。
     * 获取方式见 docs/SETUP.md「百度网盘凭据」一节。
     */
    const val BAIDU_BDUSS = ""
    const val BAIDU_STOKEN = ""
    const val BAIDU_BDSTOKEN = ""

    /**
     * 必须是根目录下的普通文件夹。
     * 不要用 /apps/xxx —— 那是「我的应用数据」，网盘客户端默认不显示。
     */
    const val BAIDU_DIR = "/录音记录"

    // ---- 阿里云百炼（语音转写 + 摘要）----
    /** 同一个 key 既用于 qwen3-asr-flash 转写，也用于 qwen-plus 摘要 */
    const val ALIYUN_API_KEY = ""
}
