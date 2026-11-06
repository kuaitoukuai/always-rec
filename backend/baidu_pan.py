# -*- coding: utf-8 -*-
"""
百度网盘网页版接口客户端（BDUSS cookie 认证）
==============================================
不走开放平台 OAuth，不需要 appKey/appSecret —— 沿用你 Zlibrary2026.8.3 那套
网页版接口方案，只做了工程化封装。

接口：/api/list、/api/precreate、/api/create、superfile2、/api/filemetas
认证：BDUSS + STOKEN cookie + bdstoken（来自 baidu_cookies.json）
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import time
from typing import Any

import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
APP_ID = "250528"
CHUNK = 4 * 1024 * 1024


class BaiduPanError(RuntimeError):
    pass


class BaiduPan:
    def __init__(self, cookie_file: str):
        with open(cookie_file, encoding="utf-8") as f:
            data = json.load(f)
        cookie_map = data.get("cookies") or {}
        bduss = cookie_map.get("BDUSS")
        if not bduss:
            raise BaiduPanError(f"{cookie_file} 里没有 BDUSS，需要重新登录抓 cookie")
        self.bdstoken = data.get("bdstoken", "")
        self.cookie = f"BDUSS={bduss}"
        if cookie_map.get("STOKEN"):
            self.cookie += f"; STOKEN={cookie_map['STOKEN']}"
        self.sess = requests.Session()

    # ---------- 基础设施 ----------

    def _headers(self, referer: str = "https://pan.baidu.com/disk/home") -> dict:
        return {"User-Agent": UA, "Referer": referer, "Cookie": self.cookie}

    def _base_params(self) -> dict:
        return {
            "app_id": APP_ID,
            "channel": "chunlei",
            "web": 1,
            "clienttype": 0,
            "bdstoken": self.bdstoken,
        }

    @staticmethod
    def _logid() -> str:
        return base64.b64encode(f"{time.time() * 1000:.0f}.{os.getpid()}".encode()).decode()

    # ---------- 读 ----------

    def list_dir(self, remote_dir: str, limit: int = 1000) -> list[dict]:
        params = self._base_params()
        params.update(
            {"dir": remote_dir, "order": "time", "start": 0, "limit": limit, "logid": self._logid()}
        )
        r = self.sess.get(
            "https://pan.baidu.com/api/list", params=params, headers=self._headers(), timeout=30
        )
        j = r.json()
        if j.get("errno") != 0:
            raise BaiduPanError(f"list({remote_dir}) errno={j.get('errno')} {r.text[:200]}")
        return j.get("list", [])

    def exists(self, remote_dir: str) -> bool:
        try:
            self.list_dir(remote_dir, limit=1)
            return True
        except BaiduPanError:
            return False

    def mkdir(self, remote_dir: str) -> None:
        params = self._base_params()
        params.update({"isdir": 1, "rtype": 1, "logid": self._logid()})
        r = self.sess.post(
            "https://pan.baidu.com/api/create",
            params=params,
            data={
                "path": remote_dir,
                "isdir": 1,
                "block_list": json.dumps([]),
                "local_mtime": int(time.time()),
            },
            headers=self._headers(),
            timeout=30,
        )
        j = r.json()
        if j.get("errno") not in (0, -8):
            raise BaiduPanError(f"mkdir({remote_dir}) errno={j.get('errno')} {r.text[:200]}")

    def ensure_dir(self, remote_dir: str) -> None:
        cur = ""
        for part in [p for p in remote_dir.strip("/").split("/") if p]:
            cur += "/" + part
            if not self.exists(cur):
                self.mkdir(cur)

    # ---------- 写 ----------

    @staticmethod
    def _block_md5(path: str) -> list[str]:
        out = []
        with open(path, "rb") as f:
            while True:
                b = f.read(CHUNK)
                if not b:
                    break
                out.append(hashlib.md5(b).hexdigest())
        return out

    def upload(self, local_path: str, remote_dir: str, remote_name: str | None = None) -> dict:
        name = remote_name or os.path.basename(local_path)
        remote_path = remote_dir.rstrip("/") + "/" + name
        size = os.path.getsize(local_path)
        blocks = self._block_md5(local_path)

        self.ensure_dir(remote_dir)

        # 1. precreate
        params = self._base_params()
        params["logid"] = self._logid()
        r = self.sess.post(
            "https://pan.baidu.com/api/precreate",
            params=params,
            data={
                "path": remote_path,
                "size": size,
                "isdir": 0,
                "autoinit": 1,
                "block_list": json.dumps(blocks),
                "rtype": 1,
            },
            headers=self._headers(),
            timeout=60,
        )
        j = r.json()
        if j.get("errno") not in (0, -8):
            raise BaiduPanError(f"precreate errno={j.get('errno')} {r.text[:200]}")

        uploadid = j.get("uploadid")
        if not uploadid:
            # 秒传命中，文件已在云端
            return {"path": remote_path, "size": size, "instant": True}

        # 2. 分片
        with open(local_path, "rb") as f:
            seq = 0
            while True:
                chunk = f.read(CHUNK)
                if not chunk:
                    break
                self._upload_part(remote_path, uploadid, seq, chunk)
                seq += 1

        # 3. create
        params = self._base_params()
        params.update({"isdir": 0, "rtype": 1, "logid": self._logid()})
        r = self.sess.post(
            "https://pan.baidu.com/api/create",
            params=params,
            data={
                "path": remote_path,
                "size": size,
                "uploadid": uploadid,
                "block_list": json.dumps(blocks),
                "local_mtime": int(time.time()),
            },
            headers=self._headers(),
            timeout=60,
        )
        j = r.json()
        if j.get("errno") != 0:
            raise BaiduPanError(f"create errno={j.get('errno')} {r.text[:200]}")

        return {"path": remote_path, "size": size, "uploadid": uploadid, "instant": False}

    def _upload_part(self, remote_path: str, uploadid: str, seq: int, chunk: bytes) -> None:
        params = self._base_params()
        params.update(
            {
                "method": "upload",
                "BDUSS": self.cookie.split("BDUSS=")[1].split(";")[0],
                "path": remote_path,
                "uploadid": uploadid,
                "uploadsign": 0,
                "partseq": seq,
            }
        )
        r = self.sess.post(
            "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2",
            params=params,
            files={"file": ("blob", chunk, "application/octet-stream")},
            headers=self._headers(),
            timeout=120,
        )
        j = r.json()
        if "md5" not in j and j.get("errno") not in (0, None):
            raise BaiduPanError(f"upload part {seq} failed: {r.text[:200]}")

    # ---------- 下载 ----------

    def get_dlink(self, remote_path: str) -> str:
        params = self._base_params()
        params.update(
            {"target": json.dumps([remote_path]), "dlink": 1, "logid": self._logid()}
        )
        r = self.sess.get(
            "https://pan.baidu.com/api/filemetas",
            params=params,
            headers=self._headers(),
            timeout=30,
        )
        j = r.json()
        if j.get("errno") != 0:
            raise BaiduPanError(f"filemetas errno={j.get('errno')} {r.text[:200]}")
        info = j.get("info") or []
        if not info:
            raise BaiduPanError(f"filemetas 没有返回 {remote_path}")
        return info[0]["dlink"]

    def download(self, remote_path: str, local_path: str) -> int:
        dlink = self.get_dlink(remote_path)
        os.makedirs(os.path.dirname(local_path) or ".", exist_ok=True)
        with self.sess.get(
            dlink, headers=self._headers(), stream=True, timeout=120, allow_redirects=True
        ) as r:
            r.raise_for_status()
            total = 0
            with open(local_path, "wb") as f:
                for block in r.iter_content(256 * 1024):
                    if block:
                        f.write(block)
                        total += len(block)
        return total
