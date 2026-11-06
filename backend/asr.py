# -*- coding: utf-8 -*-
"""语音转写。本地 faster-whisper 优先，也可切云端。"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Protocol

import avcompat  # noqa: F401  必须跑在 faster_whisper 之前，修复 PyAV 19 参数不兼容



@dataclass
class Segment:
    start: float   # 相对片段开头的秒数
    end: float
    text: str


# ---------------------------------------------------------------------------
# Whisper 幻觉过滤
#
# Whisper 在低信噪比（远场、噪声、手机外放）片段上会"编"出训练集里的常见文本，
# 中文最典型的就是 YouTube 结尾语。这些必须剔除，否则日报里全是垃圾。
# ---------------------------------------------------------------------------
HALLUCINATION_PATTERNS = (
    "请不吝点赞", "订阅", "转发", "打赏", "明镜", "点点栏目",
    "字幕由", "字幕组", "中文字幕", "感谢观看", "谢谢观看", "未完待续",
    "Amara", "amara.org", "MING PAO", "下集再见",
)

# 这些是提示词被模型复读的表现，单独判一次
PROMPT_ECHO_HINTS = ("这些是普通话", "专业术语。", "日常对话的日常对话")


def _is_junk(text: str, seg) -> str | None:
    """返回丢弃原因，None 表示保留。"""
    t = text.strip()
    if len(t) <= 1:
        return "too_short"

    # 模型自评：这段其实没人说话 / 概率太低 / 重复率过高
    if getattr(seg, "no_speech_prob", 0.0) > 0.6:
        return "no_speech"
    if getattr(seg, "avg_logprob", 0.0) < -1.0:
        return "low_logprob"
    if getattr(seg, "compression_ratio", 0.0) > 2.4:
        return "repetitive"

    for p in HALLUCINATION_PATTERNS:
        if p in t:
            return f"hallucination:{p}"
    for p in PROMPT_ECHO_HINTS:
        if p in t:
            return f"prompt_echo:{p}"
    return None



class Transcriber(Protocol):
    def transcribe(self, path: str) -> list[Segment]: ...


class LocalWhisper:
    """
    本地 faster-whisper。

    模型选择建议（中文）：
      tiny   39MB   最慢也最快，中文勉强能看，只适合验证链路
      base  145MB   中文错字多，不建议
      small 484MB   中文可用，日常记录够，8 核 CPU 约 5~8x 实时
      medium 1.5GB  中文较好，CPU 慢，建议配 GPU
    """

    _cache: dict[str, "LocalWhisper"] = {}

    def __init__(self, cfg: dict):
        from faster_whisper import WhisperModel

        self.language = cfg.get("language", "zh")
        self.beam_size = cfg.get("beam_size", 5)
        self.initial_prompt = cfg.get("initial_prompt") or None
        self.vad_threshold = float(cfg.get("vad_threshold", 0.5))
        self.dropped: list[tuple[str, str]] = []
        self.model = WhisperModel(
            cfg.get("model", "small"),
            device=cfg.get("device", "cpu"),
            compute_type=cfg.get("compute_type", "int8"),
            download_root=cfg.get("download_root") or None,
        )
        self.name = cfg.get("model", "small")

    @classmethod
    def get(cls, cfg: dict) -> "LocalWhisper":
        key = f'{cfg.get("model")}|{cfg.get("device")}|{cfg.get("compute_type")}'
        if key not in cls._cache:
            cls._cache[key] = cls(cfg)
        return cls._cache[key]

    def transcribe(self, path: str) -> list[Segment]:
        segments, _info = self.model.transcribe(
            path,
            language=self.language,
            beam_size=self.beam_size,
            vad_filter=True,
            vad_parameters={
                "min_silence_duration_ms": 500,
                "threshold": self.vad_threshold,
            },
            initial_prompt=self.initial_prompt,
            condition_on_previous_text=False,
            # 关掉"按前文续写"，否则噪声段会被前一句带着一路编下去
            without_timestamps=False,
        )
        out: list[Segment] = []
        for s in segments:
            t = (s.text or "").strip()
            if not t:
                continue
            reason = _is_junk(t, s)
            if reason:
                self.dropped.append((reason, t[:40]))
                continue
            out.append(Segment(float(s.start), float(s.end), t))
        return out


class GroqWhisper:
    """云端 Groq whisper-large-v3-turbo，速度快、免费额度大，需要 GROQ_API_KEY。"""

    def __init__(self, cfg: dict):
        import requests

        self.key = os.environ.get("GROQ_API_KEY", "")
        if not self.key:
            raise RuntimeError("未设置 GROQ_API_KEY 环境变量")
        self.requests = requests
        self.model = cfg.get("model", "whisper-large-v3-turbo")
        self.language = cfg.get("language", "zh")

    def transcribe(self, path: str) -> list[Segment]:
        with open(path, "rb") as f:
            r = self.requests.post(
                "https://api.groq.com/openai/v1/audio/transcriptions",
                headers={"Authorization": f"Bearer {self.key}"},
                files={"file": (os.path.basename(path), f, "audio/aac")},
                data={
                    "model": self.model,
                    "language": self.language,
                    "response_format": "verbose_json",
                    "timestamp_granularities[]": "segment",
                },
                timeout=300,
            )
        r.raise_for_status()
        j = r.json()
        out: list[Segment] = []
        for s in j.get("segments", []):
            t = (s.get("text") or "").strip()
            if not t:
                continue
            if any(p in t for p in HALLUCINATION_PATTERNS):
                continue
            out.append(Segment(float(s["start"]), float(s["end"]), t))
        return out


class DashScopeASR:
    """
    阿里云百炼 qwen3-asr-flash —— 中文效果远好于 whisper small。

    同一段 50 秒的远场对话实测对比：
      whisper small: 「宇宙你好 / 我看你們公務中好法 / 鐵盒 / 六成總提取問答附件」
      qwen3-asr:     「余总你好，我看你们公众号发一篇档案管理系统上线的推文…
                      主要是自动根据项目性质在业务系统的流程中提取相应的文档附件」

    限制：单次请求对音频时长有上限。本项目**不做切片**，而是把采集端单片
    上限压到 2 分钟（Capture.kt 的 maxSegmentMs），从源头保证不会超限。
    之前注释写"超过 4 分钟会切片"是错的，实际没有实现切片，已改正。
    """
    def __init__(self, cfg: dict):
        import requests

        self.requests = requests
        self.key = cfg.get("api_key") or os.environ.get("DASHSCOPE_API_KEY", "")
        if not self.key:
            raise RuntimeError("未配置 asr.api_key 或 DASHSCOPE_API_KEY")
        self.model = cfg.get("model", "qwen3-asr-flash")
        self.base_url = cfg.get(
            "base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ).rstrip("/")
        self.dropped: list[tuple[str, str]] = []

    def transcribe(self, path: str) -> list[Segment]:
        import base64

        # 直接读 ADTS AAC 的原始字节：qwen3-asr-flash 原生支持 audio/aac，
        # 不需要解码成 wav，省掉 PyAV / ffmpeg 这一整条依赖
        with open(path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        payload = {
            "model": self.model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_audio",
                            "input_audio": {"data": f"data:audio/aac;base64,{b64}"},
                        }
                    ],
                }
            ],
            "asr_options": {"enable_words": True, "enable_timestamp": True},
        }
        r = self.requests.post(
            f"{self.base_url}/chat/completions",
            headers={"Authorization": f"Bearer {self.key}", "Content-Type": "application/json"},
            json=payload,
            timeout=300,
        )
        if r.status_code != 200:
            raise RuntimeError(f"ASR {r.status_code}: {r.text[:300]}")

        j = r.json()
        text = (j["choices"][0]["message"]["content"] or "").strip()
        if not text:
            return []
        if any(p in text for p in HALLUCINATION_PATTERNS):
            self.dropped.append(("hallucination", text[:40]))
            return []
        # 云端返回整段文本，不做句级切分；片段级时间戳来自文件名，够用
        return [Segment(0.0, 0.0, text)]


def build(cfg: dict) -> Transcriber:
    backend = cfg.get("backend", "local")
    if backend == "local":
        return LocalWhisper.get(cfg)
    if backend == "dashscope":
        return DashScopeASR(cfg)
    if backend == "groq":
        return GroqWhisper(cfg)
    raise ValueError(f"未知的 asr.backend: {backend}")
