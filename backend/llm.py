# -*- coding: utf-8 -*-
"""阿里云百炼 LLM 调用（OpenAI 兼容）。"""
from __future__ import annotations

import json
import os
from pathlib import Path

import requests

CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
HERE = Path(__file__).resolve().parent


def _load_key() -> str:
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if key:
        return key
    try:
        cfg = json.loads((HERE / "config.json").read_text(encoding="utf-8"))
        return cfg.get("asr", {}).get("api_key", "")
    except Exception:
        return ""


def chat(prompt: str, model: str = "qwen-plus", temperature: float = 0.3, timeout: int = 120) -> str | None:
    key = _load_key()
    if not key:
        return None
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
    }
    try:
        r = requests.post(
            CHAT_URL,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json=payload,
            timeout=timeout,
        )
        if r.status_code != 200:
            return None
        j = r.json()
        text = (j["choices"][0]["message"]["content"] or "").strip()
        return text or None
    except Exception:
        return None
