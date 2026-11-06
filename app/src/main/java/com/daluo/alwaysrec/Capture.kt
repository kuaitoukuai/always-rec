package com.daluo.alwaysrec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 采集参数。默认值按「24 小时常驻 + 语音转写」调过，通常只需要改这里。
 *
 * 关于采样率：MDPI 的 18 台安卓机实测表明采样率对功耗没有统计显著影响，
 * 所以这里选 16kHz 不是为了省电，而是为了省 3 倍存储、3 倍上传流量，
 * 并且 Whisper 原生就是 16kHz，后端不需要再重采样。
 */
data class RecConfig(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,
    /** AAC-LC 码率。32kbps 单声道对语音足够，约 14MB/小时。 */
    val bitRate: Int = 32_000,
    /** 预缓冲时长：VAD 触发时把这段时间一起写出去，避免切掉话头。 */
    val prerollMs: Int = 8_000,
    /** 连续检测到这么多人声才真正起录，用来抵抗关门、碰撞这类瞬时噪声。 */
    val onsetMs: Int = 60,
    /** 静音持续这么久才封片，避免一句话被切成碎片。 */
    val hangoverMs: Int = 5_000,
    /** 单片最长时长，兜底防止一个人连续说 3 小时生成一个巨大文件。 */
    /**
     * 单片上限。不要调到 3 分钟以上 ——
     * qwen3-asr-flash 单次请求对音频时长有上限，超了会整段识别失败。
     * 2 分钟留了安全余量，坏一片也只损失 2 分钟。
     */
    val maxSegmentMs: Int = 120_000,
    /** 短于这个时长的碎片直接丢弃，多半是噪声误触发。 */
    val minSegmentMs: Int = 1_000,
    /**
     * true  = VAD 触发才落盘（省 60~80% 存储，适合生活记录）
     * false = 全程连续落盘（一个字都不漏，适合会议/取证，存储约 350MB/天）
     */
    val vadEnabled: Boolean = true,
) {
    val frameBytes: Int get() = sampleRate * frameMs / 1000 * 2
    val prerollFrames: Int get() = prerollMs / frameMs
}

/**
 * 轻量能量 VAD：自适应噪声底 + 相对阈值。
 *
 * 为什么不直接上 Silero VAD：那是 DNN，每 20ms 推理一次，24 小时要跑 400 多万次，
 * CPU 唤醒的耗电会盖过它省下的那点存储。常驻场景用纯计算量极小的 GMM/能量法更合适。
 *
 * 想要更高精度可以换成 gkonovalov/android-vad 的 webrtc 模块：
 *   VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.VERY_AGGRESSIVE)
 * 接口语义完全一样，换掉 update() 内部实现即可。
 */
class EnergyVad {
    /** 噪声底，用双速跟随：下降快、上升慢，防止持续人声把阈值一直顶高。 */
    private var noiseFloor = 400.0

    /** 返回这一帧是否包含人声。 */
    fun update(pcm: ByteArray, len: Int): Boolean {
        val samples = len / 2
        if (samples <= 0) return false

        var sum = 0.0
        var i = 0
        while (i < samples) {
            val raw = ((pcm[i * 2 + 1].toInt() and 0xFF) shl 8) or (pcm[i * 2].toInt() and 0xFF)
            val s = if (raw >= 0x8000) raw - 0x10000 else raw
            sum += s.toDouble() * s
            i++
        }
        val rms = sqrt(sum / samples)

        noiseFloor = if (rms < noiseFloor) {
            noiseFloor * 0.90 + rms * 0.10
        } else {
            noiseFloor * 0.999 + rms * 0.001
        }

        // 约 +9.5dB 的相对门限，同时设绝对下限防止静音时过度灵敏
        val threshold = max(noiseFloor * 3.0, 800.0)
        return rms > threshold
    }
}

/** 定长帧环形缓冲，用来实现 pre-roll。容量小（8 秒约 256KB），常驻内存无压力。 */
class PcmRing(private val capacity: Int) {
    private val frames = ArrayDeque<ByteArray>(capacity)

    fun push(frame: ByteArray) {
        frames.addLast(frame)
        while (frames.size > capacity) frames.removeFirst()
    }

    fun drainTo(sink: (ByteArray) -> Unit) {
        while (frames.isNotEmpty()) sink(frames.removeFirst())
    }
}

/**
 * 把 PCM 实时编码成 AAC 并封装成 ADTS。
 *
 * 为什么用 ADTS 而不是 MP4 容器：ADTS 每一帧自带头，文件被截断也仍然可播，
 * 而且 ffmpeg / whisper / 各家云 ASR 都能直接吃，后端零适配成本。
 */
class AacChunkEncoder(
    private val sampleRate: Int,
    private val bitRate: Int,
    private val out: OutputStream,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var released = false

    var bytesWritten = 0L
        private set

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ByteArray, len: Int) {
        if (released) return
        var offset = 0
        while (offset < len) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val ib = codec.getInputBuffer(idx) ?: return
            ib.clear()
            val n = min(ib.remaining(), len - offset)
            ib.put(pcm, offset, n)
            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
            ptsUs += n / 2L * 1_000_000L / sampleRate
            offset += n
            drain(eos = false)
        }
    }

    /** 冲刷编码器并关闭。调用后本实例不可再用。 */
    fun finish() {
        if (released) return
        released = true
        runCatching {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(eos = true)
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { out.flush() }
        runCatching { out.close() }
    }

    private fun drain(eos: Boolean) {
        var guard = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    // 冲刷阶段允许有限次重试，防止个别机型不退 EOS 造成死循环
                    if (++guard > 100) return
                }

                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit

                idx >= 0 -> {
                    val ob = codec.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (ob != null && info.size > 0 && !isConfig) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val payload = ByteArray(info.size)
                        ob.get(payload)
                        out.write(adtsHeader(payload.size))
                        out.write(payload)
                        bytesWritten += payload.size + 7
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                // 其他未知负值，直接退出，避免 while 空转
                else -> return
            }
        }
    }

    private fun adtsHeader(payloadLen: Int): ByteArray {
        val frameLen = payloadLen + 7
        val sfi = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 8
        }
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((1 shl 6) or (sfi shl 2)).toByte(),
            0x40.toByte(),
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}

data class RecordingState(
    val active: Boolean,
    val segmentId: String? = null,
    val seconds: Int = 0,
)

/**
 * 分片状态机：把连续的 PCM 流切成一个个语义完整的语音片段。
 *
 * 状态流转：
 *   空闲 --(连续 onsetMs 人声)--> 录音中 --(静音 hangoverMs / 达到 maxSegmentMs)--> 空闲
 *
 * 预缓冲在 idle 态持续累积最近 prerollMs 的音频，一旦触发就先把它整段吐出来，
 * 这样「开口第一个字」不会被切掉——这是纯触发起录方案最常翻车的地方。
 */
class SegmentEngine(
    private val cfg: RecConfig,
    private val spool: Spool,
    private val onState: (RecordingState) -> Unit,
) {
    private val vad = EnergyVad()
    private val ring = PcmRing(cfg.prerollFrames)

    private var encoder: AacChunkEncoder? = null
    private var file: java.io.File? = null
    private var segmentId: String? = null
    private var voiceFrames = 0
    private var silenceFrames = 0
    private var totalFrames = 0
    private var startedAt = 0L

    fun onPcm(data: ByteArray, len: Int) {
        if (len <= 0) return
        val frame = data.copyOf(len)
        val speech = if (cfg.vadEnabled) vad.update(frame, frame.size) else true

        val enc = encoder
        if (enc == null) {
            ring.push(frame)
            if (!cfg.vadEnabled) {
                open()
                return
            }
            if (speech) {
                voiceFrames++
                if (voiceFrames * cfg.frameMs >= cfg.onsetMs) open()
            } else {
                voiceFrames = 0
            }
            return
        }

        enc.write(frame, frame.size)
        totalFrames++
        if (speech) silenceFrames = 0 else silenceFrames++

        val tooLong = totalFrames.toLong() * cfg.frameMs >= cfg.maxSegmentMs
        val hungOver = cfg.vadEnabled && silenceFrames.toLong() * cfg.frameMs >= cfg.hangoverMs
        if (tooLong || hungOver) close()
    }

    /** 服务停止时调用，把当前未写完的片段正常收尾。 */
    fun finish() = close()

    private fun open() {
        val id = spool.newId()
        val f = spool.newAudioFile(id)

        // 个别机型缺 AAC 编码器，或者编码器被其他进程占满时会抛异常。
        // 这里降级处理：丢掉这一段，不要让它把整个采集线程打挂。
        val enc = try {
            AacChunkEncoder(cfg.sampleRate, cfg.bitRate, FileOutputStream(f))
        } catch (_: Throwable) {
            f.delete()
            ring.drainTo { }
            voiceFrames = 0
            return
        }

        encoder = enc
        file = f
        segmentId = id
        voiceFrames = 0
        silenceFrames = 0
        totalFrames = 0

        // 把预缓冲整段先写进去，补回话头。
        // 注意时间戳要往回扣：这 8 秒音频是在触发之前录的，
        // 如果直接用"现在"当开始时间，整条时间线会系统性偏晚最多 8 秒。
        val openedAt = System.currentTimeMillis()
        var preFrames = 0L
        ring.drainTo {
            enc.write(it, it.size)
            totalFrames++
            preFrames++
        }
        startedAt = openedAt - preFrames * cfg.frameMs

        onState(RecordingState(true, id, 0))
    }

    private fun close() {
        val enc = encoder ?: return
        val f = file
        val id = segmentId
        val durationMs = totalFrames.toLong() * cfg.frameMs
        val written = enc.bytesWritten

        encoder = null
        file = null
        segmentId = null
        voiceFrames = 0
        silenceFrames = 0
        totalFrames = 0
        enc.finish()

        if (f != null && id != null && durationMs >= cfg.minSegmentMs && written > 0) {
            spool.commit(id, f, durationMs, startedAt)
        } else {
            f?.delete()
        }
        onState(RecordingState(false))
    }
}
