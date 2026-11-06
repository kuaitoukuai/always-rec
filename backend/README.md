# 后端：录音后处理管线

手机只负责录，这里负责把录音变成人能读的东西。

```
百度网盘 / 本地目录
      ↓ 收集 .aac 片段（文件名自带开始时间）
   转写（阿里云 qwen3-asr-flash / 本地 whisper / Groq）
      ↓ 句级清洗（幻觉过滤、繁转简、重复折叠）
   时间线合并
      ↓
   output/YYYY-MM-DD.md
```

## 一、ASR 方案对比（同一段 50 秒远场对话实测）

| 方案 | 输出 | 耗时 | 结论 |
|---|---|---|---|
| 本地 faster-whisper **small** | 「宇宙你好 / 我看你們公務中好法 / 鐵盒 / 六成總提取問答附件」 | 3.5 分钟 / 6.6 分钟音频 | **不可用**，错字多且吐繁体 |
| 阿里云 **qwen3-asr-flash** | 「余总你好，我看你们公众号发一篇档案管理系统上线的推文…主要是自动根据项目性质在业务系统的流程中提取相应的文档附件」 | **25 秒 / 6.6 分钟音频** | **可用**，且快 8 倍 |

结论：远场手机录音必须走云端。本地模型只适合近场、安静环境的语音笔记。

配置在 `config.json` 的 `asr.backend`：`dashscope`（默认）/ `local` / `groq`。

## 二、用法

```bash
PY="C:/Users/admin/.workbuddy/binaries/python/envs/default/Scripts/python.exe"

# 处理本地目录
$PY pipeline.py --local ./test_clips

# 从百度网盘拉取（按日期目录）
$PY pipeline.py --pan
$PY pipeline.py --pan --date 2026-11-06

# 只跑前 3 片，验证配置
$PY pipeline.py --local ./test_clips --limit 3
```

输出在 `output/YYYY-MM-DD.md`，已处理的片段记录在 `data/state.json`，重跑不会重复转写（删掉它可强制重跑）。

## 三、文件说明

| 文件 | 作用 |
|---|---|
| `pipeline.py` | 主流程：收集 → 转写 → 清洗 → 合并 → 输出 |
| `asr.py` | 三种转写后端 + 幻觉过滤规则 |
| `mimo_asr.py` | 音频转 16k wav base64 的工具（阿里云/MiMo 共用） |
| `baidu_pan.py` | 百度网盘客户端（网页版接口 + BDUSS cookie） |
| `avcompat.py` | PyAV 19 与 faster-whisper 1.2.1 的参数不兼容垫片 |
| `config.json` | 全部配置 |

## 四、三个必须知道的坑

**1. PyAV 19 与 faster-whisper 1.2.1 不兼容**
`av.open()` 在 PyAV 19 移除了 `metadata_errors` 参数，而 faster-whisper 仍在传，
会抛 `TypeError`。`avcompat.py` 做了垫片，**任何用到音频解码的模块都必须在
import faster_whisper 之前 import 它**。

**2. Whisper 幻觉**
低信噪比片段上 Whisper 会编出「请不吝点赞订阅转发打赏支持明镜与点点栏目」这类
YouTube 结尾语，实测 23 片里出现 39 条。`asr.py` 里有三层过滤：
`no_speech_prob / avg_logprob / compression_ratio` + 短语黑名单 + 句子级重复折叠。
换阿里云后这个问题自然消失。

**3. initial_prompt 会被复读**
给 Whisper 传 `initial_prompt` 引导专业术语，结果模型在噪声段直接把提示词念出来。
已默认置空。

## 五、密钥说明

`config.json` 保存阿里云 API Key 和百度网盘 cookie 文件路径，
**该文件已被 `.gitignore` 排除，不会提交**。首次使用请：

```bash
cp config.example.json config.json
# 填入自己的 api_key 与 cookie_file 绝对路径
```

百度网盘走 cookie 不走 key：`baidu.cookie_file` 指向抓取下来的
`baidu_cookies.json`。BDUSS 通常几个月有效，过期需重新登录浏览器抓一次。
获取步骤见 `docs/SETUP.md`。

## 六、成本估算

qwen3-asr-flash 按音频时长计费。一天 4 小时有效人声 ≈ 14400 秒，
成本在个位数人民币量级。真正该省的不是转写费，而是**别把静音段传上去**——
手机端 VAD 已经做掉了 80% 的无效音频。

## 七、还没做

- [ ] 说话人分离（谁在说）—— 没有这个，多人对话的时间线无法读
- [ ] 环境音与本人对话的区分（电视、短视频会被当成内容）
- [ ] 分层摘要（片段 → 小时 → 日）
- [ ] 时间线 embedding + 自然语言检索
