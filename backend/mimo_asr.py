# -*- coding: utf-8 -*-
"""
小米 MiMo ASR 客户端
====================
云端语音识别，OpenAI 兼容接口。

文档：https://platform.xiaomimimo.com/docs/zh-CN/api/audio/Speech-Recognition
端点：https://api.xiaomimimo.com/v1/chat/completions
模型：mimo-v2.5-asr
认证：Authorization: Bearer $MIMO_API_KEY

限制：只接受 mp3 / wav 的 Base64 data URL，所以 aac 要先解码转 16k 单声道 wav。
"""
from __future__ import annotations

import base64
import io
import os
import wave

import requests

import avcompat  # noqa: F401  必须跑在 faster_whisper 之前


def to_wav_base64(path: str, sampling_rate: int = 16000) -> str:
    """任意音频 → 16kHz 单声道 16bit WAV 的 base64（不带 data: 前缀）。"""
    import numpy as np
    from faster_whisper.audio import decode_audio

    audio = decode_audio(path, sampling_rate=sampling_rate)
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")

    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sampling_rate)
        w.writeframes(pcm.tobytes())
    return base64.b64encode(buf.getvalue()).decode()


def transcribe(
    path: str,
    api_key: str | None = None,
    model: str = "mimo-v2.5-asr",
    base_url: str = "https://api.xiaomimimo.com/v1",
    timeout: int = 300,
) -> dict:
    key = api_key or os.environ.get("MIMO_API_KEY", "")
    if not key:
        raise RuntimeError("没有 MIMO_API_KEY")

    b64 = to_wav_base64(path)
    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {"data": f"data:audio/wav;base64,{b64}"},
                    }
                ],
            }
        ],
    }
    r = requests.post(
        f"{base_url}/chat/completions",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json=payload,
        timeout=timeout,
    )
    return {"status": r.status_code, "body": r.text}
