# -*- coding: utf-8 -*-
"""
PyAV 兼容垫片。

PyAV 19 移除了 av.open 的 metadata_errors 参数，而 faster-whisper 1.2.1
内部仍在传，会抛：
    TypeError: open() got an unexpected keyword argument 'metadata_errors'

任何用到 faster_whisper.audio.decode_audio 的模块，都必须在导入 faster_whisper
之前先 import 本模块。
"""
from __future__ import annotations

try:
    import av as _av

    if "metadata_errors" not in (_av.open.__doc__ or ""):
        _av_open_orig = _av.open

        def _av_open_compat(file, mode="r", **kwargs):
            kwargs.pop("metadata_errors", None)
            return _av_open_orig(file, mode, **kwargs)

        _av.open = _av_open_compat
except Exception:  # pragma: no cover
    pass
