package com.daluo.alwaysrec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻录音服务。
 *
 * 关于「静默」，有三条平台硬限制，代码绕不过去，必须清楚：
 *  1. 麦克风录音必须挂前台服务，通知栏必然有一条通知（Android 8+ 强制）。
 *  2. Android 12+ 状态栏会有麦克风使用指示器，非 root 无法隐藏。
 *  3. Android 14+ 禁止从 BOOT_COMPLETED 启动 microphone 类型的前台服务，
 *     所以重启手机后必须手动打开 App 才能重新开始录音。
 *
 * 因此这里的目标不是「无痕」，而是「无感」：通知最低优先级、不响不震、折叠成一行。
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.daluo.alwaysrec.action.START"
        const val ACTION_STOP = "com.daluo.alwaysrec.action.STOP"

        private const val CHANNEL_ID = "always_rec"
        private const val NOTI_ID = 1001

        private val _state = MutableStateFlow(RecordingState(false))
        val state: StateFlow<RecordingState> = _state.asStateFlow()

        @Volatile
        var running: Boolean = false
            private set

        /** 必须从可见的 Activity 里调用，否则 Android 14+ 会抛 SecurityException。 */
        fun start(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(i) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var heartbeatJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!hasMicPermission()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNow()
                startCapture()
            }
        }

        /*
         * 这里刻意返回 START_NOT_STICKY 而不是 START_STICKY。
         * 原因：进程被系统杀掉后若被 START_STICKY 拉起，此时 App 处于后台状态，
         * 而 Android 14+ 禁止后台启动 microphone 类型前台服务，会直接抛
         * ForegroundServiceStartNotAllowedException 导致崩溃循环。
         * 正确做法是让用户重新打开 App —— 这是平台限制，不是代码能绕过的。
         */
        return START_NOT_STICKY
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundNow() {
        createChannel()
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("录音中")
            .setContentText("正在采集环境音频")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        // Android 16 要求在启动后 5 秒内完成，这里紧跟 startCapture 之前调用
        ServiceCompat.startForeground(this, NOTI_ID, noti, type)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "常驻录音",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                description = "录音服务运行时显示，无法关闭"
            }
            nm.createNotificationChannel(ch)
        }
    }

    /**
     * 监听 WiFi：一连上就排一次上传 + 转写。
     * 周期任务最小只能设 15 分钟，靠这个回调才能做到"连上就传"。
     */
    private fun registerWifiWatcher() {
        if (netCallback != null) return
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (usable) {
                        UploadWorker.runOnWifi(this@RecorderService)
                        TranscribeWorker.enqueue(this@RecorderService)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }
    }

    private fun unregisterWifiWatcher() {
        val cb = netCallback ?: return
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        }
        netCallback = null
    }

    /**
     * 心跳：每 10 分钟上报一次状态。
     *
     * 这是判断"服务有没有被系统杀掉"的唯一可靠手段 —— 服务活着心跳就在，
     * 被杀之后心跳自然停。电脑端看最后一次心跳距今多久就知道了。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running) {
                runCatching {
                    val pending = Spool(this@RecorderService).pending().size
                    Heartbeat.beat(this@RecorderService, running, pending)
                }
                delay(10 * 60 * 1000L)
            }
        }
    }

    private fun startCapture() {
        if (running) return
        running = true

        // 上一次如果被 ROM 杀掉，可能留下只有 .aac 没有 .meta 的片段，
        // 那些片段永远不会进上传队列，这里先补 meta 救回来。
        val rescued = runCatching { Spool(this).recoverOrphans() }.getOrDefault(0)
        if (rescued > 0) {
            android.util.Log.i("AlwaysRec", "恢复 $rescued 个孤儿片段")
            TranscribeWorker.enqueue(this)
            UploadWorker.runOnWifi(this)
        }

        registerWifiWatcher()
        startHeartbeat()
        captureJob = scope.launch { captureLoop() }
    }

    private fun stopCapture() {
        running = false
        captureJob?.cancel()
        captureJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        unregisterWifiWatcher()
        _state.value = RecordingState(false)
    }

    private suspend fun captureLoop() {
        val cfg = RecConfig()
        val spool = Spool(this)
        val engine = SegmentEngine(cfg, spool) { st ->
            _state.value = st
            // 片段一封口就排一次转写，用户不用等到上传时才看到文字
            if (!st.active) TranscribeWorker.enqueue(this)
        }

        var recorder: AudioRecord? = null
        try {
            while (scope.isActive && running) {
                recorder = openRecorder(cfg)
                if (recorder == null) {
                    // 麦克风被占用（通话中、其他 App 正在录音）时等一会儿重试
                    delay(2_000)
                    continue
                }

                recorder.startRecording()
                val buf = ByteArray(cfg.frameBytes)

                while (running) {
                    val n = recorder.read(buf, 0, buf.size)
                    when {
                        n > 0 -> engine.onPcm(buf, n)
                        // 插拔耳机、蓝牙切换会导致 AudioRecord 断开，必须重开
                        n == AudioRecord.ERROR_DEAD_OBJECT -> break
                        n < 0 -> break
                    }
                }

                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                recorder = null
                if (running) delay(500)
            }
        } catch (t: Throwable) {
            _state.value = RecordingState(false)
        } finally {
            engine.finish()
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            running = false
            _state.value = RecordingState(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(cfg: RecConfig): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            cfg.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null

        /*
         * 缓冲区给到 minBuf 的 4 倍以上，至少 1 秒。
         * 这是防丢帧最关键的一步：缓冲区不足时 AudioRecord 会静默丢数据，
         * 音频里出现空洞，转写结果会莫名缺一段话，事后无法补救。
         *
         * 注：录音期间 audioserver 会自动持有 wakelock 防止系统休眠，
         * 应用层不需要再手动申请 PARTIAL_WAKE_LOCK（那样反而更费电）。
         */
        val bufSize = maxOf(minBuf * 4, cfg.sampleRate * 2)

        // VOICE_RECOGNITION 是专为语音识别调优的通道，部分机型自带 AGC；
        // 不可用时退回 MIC 原始通道。
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (src in sources) {
            try {
                val r = AudioRecord(
                    src,
                    cfg.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Throwable) {
                // 换下一个音源
            }
        }
        return null
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }
}
