# 架构详解

本文描述 AlwaysRec v0.1.0 的实际实现，不是愿景。所有数字来自真机实测。

## 一、总体原则

只有一条：**采集与解析彻底解耦**。

推演一下这句话的含义：

- 手机不依赖后端存活 —— 后端挂了，录音照常进行，数据堆在本地或网盘
- 后端不依赖手机在线 —— 手机没电了，后端照常处理已上传的片段
- 中间层只做「搬运」—— 网盘当异步消息队列，不参与任何计算

这条原则推导出了几乎所有设计：先落盘后上传、转写缓存、心跳独立上报、
结果用文件名携带时间戳而不是数据库。

## 二、端到端时序

```
T+0      AudioRecord 持续读 20ms 帧
T+0      能量 VAD 判定，idle 态写入 8 秒环形缓冲
T+n      连续 60ms 人声 → 触发 open()
T+n      ring buffer 整段吐出（补回话头）→ AAC 编码 → 写入 .aac
T+n+k    静音 5 秒 → close()，写 .meta（含 durationMs / startedAt）
T+n+k    ┐
         ├─→ TranscribeWorker（联网即跑，调阿里云）
         │       ↓ 写 transcripts/<id>.txt
         │       ↓ 转写成功后立即触发 UploadWorker
         │
         └─→ UploadWorker（WiFi 触发）
                 ↓ 上传 aac
                 ↓ 上传同名 txt（后端可复用）
                 ↓ 成功后 pending → done
T+m      心跳（每 10 分钟）：写本地 status.json + 上传网盘 _状态/device.json
```

电脑端：

```
每 60 秒：列网盘目录 → 下载新 aac → 转写缓存命中则跳过 → 拉 device.json
访问页面：读 transcripts → 按小时分组 → 有缓存用缓存，否则调 LLM
```

## 三、手机端模块

### Capture.kt —— 音频核心

四个类，职责严格分层：

| 类 | 职责 | 关键约束 |
|---|---|---|
| `RecConfig` | 参数集中定义 | 所有可调项都在这里 |
| `EnergyVad` | 帧级人声判定 | 必须 O(n)、无分配、可 24h 连续跑 |
| `PcmRing` | 定长帧环形缓冲 | 容量 = 8 秒 × 50 帧/秒 = 400 帧 |
| `AacChunkEncoder` | MediaCodec 编码 + ADTS 封装 | 每帧自带头，支持随时截断 |
| `SegmentEngine` | 分片状态机 | 唯一持有「当前是否在录」的状态 |

**状态机**

```
        ┌──────────┐  连续 onsetMs 人声   ┌──────────┐
        │   IDLE   │ ──────────────────→ │ RECORDING│
        │ (喂ring) │                     │ (写AAC)  │
        └──────────┘ ←────────────────── └──────────┘
                        静音 hangoverMs
                        或达到 maxSegmentMs
```

IDLE 态每个帧都 `ring.push(frame)`，RECORDING 态直接写 encoder。
`open()` 时 `ring.drainTo { enc.write(it) }` 把预缓冲整段吐出。

这是整个项目最容易写错的地方：如果先 open 再 push，第一个字必然丢。

**ADTS 头生成**

7 字节，字段固定写死（16kHz / mono / AAC-LC）：

```
FF F1  60  40  [len>>11]  [len & 7 << 5 | 1F]  FC
```

`0x60` = profile(01) << 6 | sfi(1000) << 2 | private(0) | chan_high(0)
`0x40` = chan_low(01) << 6 | frameLen_high

实测 232 帧逐字节对齐，无残留，说明封装正确。

### RecorderService.kt —— 前台服务

三条独立的协程：

| 协程 | 周期 | 职责 |
|---|---|---|
| `captureLoop` | 持续 | 读 AudioRecord，喂 SegmentEngine，异常自愈重开 |
| `heartbeatJob` | 10 分钟 | 上报状态，用于远程判断存活 |
| `NetworkCallback` | 事件驱动 | 连上 WiFi 即触发上传 |

**为什么 `START_NOT_STICKY`**

文档里单独写过一遍，这里再强调：进程被杀后若被 `START_STICKY` 拉起，
此时 App 处于后台，而 Android 14+ 禁止后台启动 microphone 类型前台服务，
会抛 `ForegroundServiceStartNotAllowedException`，形成崩溃循环。
所以宁可让用户手动重开。

**AudioRecord 缓冲区**

```kotlin
val bufSize = maxOf(minBuf * 4, sampleRate * 2)  // 至少 1 秒
```

这是防丢帧的关键。缓冲区不足时 `read()` 会静默丢数据，音频出现空洞，
转写会莫名缺一段话且无法补救。实测 4 倍缓冲下丢帧 0.78%。

**不申请 PARTIAL_WAKE_LOCK**

录音期间 `audioserver` 会自动持锁防止系统 suspend。应用层再申请一层
只会额外耗电，不会更稳。这是常见的过度防御。

### AliyunAsr.kt —— 云端转写

关键发现：`qwen3-asr-flash` **直接接受 ADTS AAC**（`data:audio/aac`），
所以手机端不需要任何音频转码，省掉整条 MediaCodec 解码 + 重采样链。

返回类型刻意设计成三态：

```kotlin
data class AsrResult(val ok: Boolean, val text: String?)
```

| ok | text | 含义 | 处理 |
|---|---|---|---|
| true | 非空 | 正常识别 | 保存文本 |
| true | null | 调用成功但没人说话 | 保存空文件（"已处理"标记） |
| false | null | 网络/API 失败 | 不保存，等重试 |

如果只用 `String?`，就无法区分「没内容」和「失败了」，
统计数据会把空片段误算成「未解析」。这是 v0.1.0 修复的一个实际 bug。

### Spool.kt —— 本地队列

```
files/rec/pending/<id>.aac + <id>.meta   ← 待上传
files/rec/done/<id>.aac    + <id>.meta   ← 已上传
```

用 `Properties` 而不是 JSON，零依赖。状态机只有两个状态，
用目录位置表达，不设独立状态字段。

**核心不变量：上传成功才移动文件。** 任何情况下都不提前删除音频。

## 四、后端模块

| 文件 | 职责 |
|---|---|
| `server.py` | Flask 页面 + 60 秒同步线程 + 小时/日概要生成 |
| `pipeline.py` | 批处理 CLI（`--local` / `--pan`） |
| `asr.py` | 三种转写后端（local / dashscope / groq）+ 幻觉过滤 |
| `baidu_pan.py` | 网盘客户端（list / upload 三步链 / download） |
| `llm.py` | LLM 调用封装 |
| `avcompat.py` | PyAV 19 与 faster-whisper 1.2.1 的参数不兼容垫片 |

### 转写缓存

`data/transcripts/<clip_id>.json`，按片段 id 存。命中即跳过，不重复计费。

实测：50 片全量转写 1 分 39 秒；全缓存命中重跑 **2 秒**（快 50 倍）。

### 幻觉过滤（三层）

Whisper 在低信噪比片段上会「编」内容，中文最典型是 YouTube 结尾语。

| 层 | 手段 | 实测效果 |
|---|---|---|
| 1 | `no_speech_prob > 0.6` / `avg_logprob < -1.0` / `compression_ratio > 2.4` | 拦掉大部分噪声段 |
| 2 | 短语黑名单（请不吝点赞 / 明镜 / 字幕由…） | 拦掉幻觉文本 |
| 3 | 句子级重复折叠（连续同一句 ≥3 次） | 拦掉「这个鱼」×7 这类卡带 |

23 个片段实测拦掉 39 条。换阿里云后该问题自然消失，但过滤逻辑保留作兜底。

### 页面数据口径

三个数字含义不同，容易混淆：

- **采集片段**：`.aac` 文件数，含噪声误触发
- **已解析**：有转写缓存的数量（空结果也算，代表"确认没人说话"）
- **对话条数**：过滤噪声后的有效句子数

## 五、并发与错误处理

| 场景 | 处理 |
|---|---|
| 插拔耳机 / 蓝牙切换 | `AudioRecord` 返回 `ERROR_DEAD_OBJECT` → 跳出内层循环 → 重开录音器 |
| 麦克风被占用（通话） | `openRecorder` 返回 null → 等 2 秒重试 |
| AAC 编码器不可用 | `open()` catch 异常 → 丢弃该片段 → 不影响采集线程 |
| 上传失败 | 保留文件，`attempts++`，20 次后跳过 |
| 网盘 token 过期 | 接口返回 errno → 单次上传失败，不影响其他片段 |
| 后端同步异常 | 线程内 catch，不中断循环 |

**没有做的**：全局崩溃上报、指标埋点、远端日志。这是有意不做的
（自用场景不需要），但对多设备部署是个缺口。

## 六、数据格式约定

对外契约只有一条：**文件名携带时间**。

```
20261106_095356_2374.aac
  └─日期──┘└─时间─┘└随机┘
```

后端解析文件名得到片段的绝对开始时间，不需要元数据文件。
这个选择让「删掉 .meta 也能重建时间线」，但也意味着**文件名一旦被改就失去时间信息**。

网盘目录结构：

```
/录音记录/
  ├── 2026-11-06/
  │   ├── 20261106_095356_2374.aac
  │   └── 20261106_095356_2374.txt   ← 转写文本，后端优先复用
  └── _状态/
      └── device.json                 ← 心跳
```

## 七、性能数据

| 操作 | 耗时 | 环境 |
|---|---|---|
| 全量编译（增量） | 30–45 秒 | AGP 8.13.1 / Gradle 9.3.1 |
| 冷编译 | 2 分 18 秒 | 同上 |
| 50 片转写 | 1 分 39 秒 | 阿里云 API |
| 50 片缓存命中 | 2 秒 | 本地读 JSON |
| 页面首屏（含 LLM 小时概要） | 3–8 秒 | 有缓存后 <1 秒 |
| 心跳上报 | <1 秒 | 含网盘上传 |
