# -*- coding: utf-8 -*-
"""LLM 调用（OpenAI 兼容接口）。

配置全部从 config.json 的 "llm" 段读取，三个字段都真的生效：
    enabled     false 时直接不调用，连请求都不发
    base_url    兼容 OpenAI 的地址，会自动补 /chat/completions
    model       模型名
    api_key_env 从这个环境变量取 key；没有就回落到 asr.api_key

之前 enabled 是写了但没判断，等于摆设 —— 评审报告 P1-7。
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import requests

DEFAULT_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_MODEL = "qwen-plus"
HERE = Path(__file__).resolve().parent


def load_config() -> dict:
    try:
        return json.loads((HERE / "config.json").read_text(encoding="utf-8"))
    except Exception:
        return {}


def llm_cfg() -> dict:
    return load_config().get("llm", {}) or {}


def enabled() -> bool:
    return bool(llm_cfg().get("enabled", True))


def _load_key(cfg: dict | None = None) -> str:
    cfg = cfg if cfg is not None else llm_cfg()
    env_name = (cfg.get("api_key_env") or "").strip()
    if env_name:
        key = os.environ.get(env_name, "")
        if key:
            return key
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if key:
        return key
    return load_config().get("asr", {}).get("api_key", "")


def _endpoint(cfg: dict) -> str:
    base = (cfg.get("base_url") or DEFAULT_BASE).rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    return base + "/chat/completions"


def chat(
    prompt: str,
    model: str | None = None,
    temperature: float = 0.3,
    timeout: int = 120,
) -> str | None:
    cfg = llm_cfg()
    if not cfg.get("enabled", True):
        return None

    key = _load_key(cfg)
    if not key:
        return None

    payload = {
        "model": model or cfg.get("model") or DEFAULT_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
    }
    try:
        r = requests.post(
            _endpoint(cfg),
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
