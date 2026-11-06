package com.daluo.alwaysrec

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 阿里云百炼 —— 语音转写 + 文本摘要。
 *
 * 实测结论：qwen3-asr-flash **直接吃 ADTS AAC**（data:audio/aac），
 * 手机端不需要做任何转码，省掉一整条 MediaCodec 解码链。
 *
 * 同一条 50 秒远场对话的表现：
 *   本地 whisper small → 「宇宙你好 / 我看你們公務中好法 / 鐵盒」
 *   qwen3-asr-flash    → 「余总你好，我看你们公众号发一篇档案管理系统上线的推文」
 */
object AliyunAsr {

    private const val CHAT_URL =
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 转写结果。ok 表示调用成功（即使内容为空），text 为 null 表示确认没有有效内容。 */
    data class AsrResult(val ok: Boolean, val text: String?)

    /** 转写单个 aac 片段。 */
    suspend fun transcribe(audio: File, apiKey: String): AsrResult = withContext(Dispatchers.IO) {
        runCatching {
            val b64 = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("model", "qwen3-asr-flash")
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("type", "input_audio")
                                        put(
                                            "input_audio",
                                            JSONObject().put("data", "data:audio/aac;base64,$b64"),
                                        )
                                    },
                                ),
                            )
                        },
                    ),
                )
            }

            val req = Request.Builder()
                .url(CHAT_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use AsrResult(false, null)
                val body = resp.body?.string() ?: return@use AsrResult(false, null)
                val text = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                    .trim()
                // 调用成功，但内容为空或全是噪声 → ok=true, text=null
                if (text.isEmpty() || isJunk(text)) AsrResult(true, null) else AsrResult(true, text)
            }
        }.getOrDefault(AsrResult(false, null))
    }

    /**
     * 生成当日概要。
     * 用 qwen-plus 而不是最大模型：这一步只做压缩，不需要推理能力，成本差 10 倍。
     */
    suspend fun summarize(timeline: String, apiKey: String, model: String = "qwen-plus"): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val prompt = """
                    下面是我今天全天录音的转写时间线。请整理成中文纪要，要求：
                    1. 先用 3-5 条要点概括今天发生了什么（有实质信息的，不要流水账）
                    2. 单独列出「待办 / 需跟进」事项，没有就写"无"
                    3. 忽略闲聊、电视、短视频、背景音，只保留有价值的内容
                    4. 总长度控制在 400 字以内

                    时间线：
                    $timeline
                """.trimIndent()

                val payload = JSONObject().apply {
                    put("model", model)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            },
                        ),
                    )
                    put("temperature", 0.3)
                }

                val req = Request.Builder()
                    .url(CHAT_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                        .trim()
                        .ifEmpty { null }
                }
            }.getOrNull()
        }

    private val HALLUCINATIONS = listOf(
        "请不吝点赞", "订阅", "转发", "打赏", "明镜", "点点栏目",
        "字幕由", "字幕组", "感谢观看", "谢谢观看", "未完待续", "Amara",
    )

    /**
     * 模型会把咳嗽、叹气、背景声标注成英文词，这些不是内容。
     * 实测手机放在桌上时，一整天的有效片段里有相当一部分是这类东西。
     */
    private val NOISE_WORDS = setOf(
        "hmm", "hm", "uh", "um", "umm", "er", "ah", "oh",
        "cough", "coughs", "coughing", "ahem", "sigh", "sighs",
        "laughter", "laughs", "laugh", "noise", "background noise",
        "silence", "music", "applause", "breath", "breathing", "sneeze",
    )

    private fun hallucinationReason(text: String): String? =
        HALLUCINATIONS.firstOrNull { text.contains(it) }

    /** 判断一段转写是否没有实际信息。 */
    fun isJunk(text: String): Boolean {
        val t = text.trim().trim('.', '。', ',', '，', '!', '！', '?', '？', '、', ' ')
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        if (NOISE_WORDS.contains(lower)) return true
        // 纯 ASCII、极短、且不含中文 → 几乎都是语气词
        if (lower.length <= 5 && lower.all { it.code < 128 } && lower.none { it.isDigit() }) {
            return true
        }
        return hallucinationReason(text) != null
    }

    fun isHallucination(text: String): Boolean = hallucinationReason(text) != null
}
