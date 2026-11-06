# -*- coding: utf-8 -*-
"""
把百度网盘里 /apps/alwaysrec 的录音迁到普通目录 /录音记录。

/apps 是「我的应用数据」，网盘客户端默认不显示，用户找不到。
迁到根目录下的普通文件夹就能直接看到。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, ".")
from baidu_pan import BaiduPan  # noqa: E402
from pipeline import load_config  # noqa: E402

CFG = load_config(Path(__file__).with_name("config.json"))
COOKIE = CFG["baidu"]["cookie_file"]
SRC = "/apps/alwaysrec"
DST = CFG["baidu"]["remote_dir"]


def main() -> int:
    p = BaiduPan(COOKIE)
    p.ensure_dir(DST)

    moved = 0
    for day in p.list_dir(SRC):
        if not day.get("isdir"):
            continue
        name = day["server_filename"]
        src_day = f"{SRC}/{name}"
        dst_day = f"{DST}/{name}"
        p.ensure_dir(dst_day)

        files = [i["server_filename"] for i in p.list_dir(src_day)]
        if not files:
            continue

        # 分批移动，一次 20 个
        for i in range(0, len(files), 20):
            batch = [f"{src_day}/{f}" for f in files[i : i + 20]]
            params = p._base_params()
            params.update(
                {
                    "opera": "move",
                    "async": 1,
                    "ondup": "newcopy",
                    "logid": p._logid(),
                }
            )
            r = p.sess.post(
                "https://pan.baidu.com/api/filemanager",
                params=params,
                data={"filelist": json.dumps(batch, ensure_ascii=False), "dest": dst_day},
                headers=p._headers(),
                timeout=60,
            )
            try:
                j = r.json()
            except Exception:
                print(f"  [{name}] 返回异常: {r.text[:150]}")
                continue
            errno = j.get("errno")
            if errno != 0:
                print(f"  [{name}] 移动失败 errno={errno} {r.text[:150]}")
            else:
                moved += len(batch)
                print(f"  [{name}] 移动 {len(batch)} 个文件 ok")

    print(f"\n共移动 {moved} 个文件到 {DST}")
    print("目标目录内容：")
    for day in p.list_dir(DST):
        if day.get("isdir"):
            sub = p.list_dir(f"{DST}/{day['server_filename']}")
            print(f"  {day['server_filename']}/  {len(sub)} 个文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
