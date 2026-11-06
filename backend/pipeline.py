# -*- coding: utf-8 -*-
"""
录音后处理管线
==============
    收集片段 → 转写 → 合并成时间线 →（可选）LLM 摘要 → 输出 Markdown

用法：
    python pipeline.py --local D:/some/rec_dir      # 处理本地目录
    python pipeline.py --pan                        # 从百度网盘拉取并处理
    python pipeline.py --pan --date 2026-11-06      # 只处理某一天
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

import asr as asr_mod

ID_RE = re.compile(r"(\d{8})_(\d{6})_(\d{4})")
HERE = Path(__file__).resolve().parent


@dataclass
class Clip:
    path: Path
    clip_id: str
    start: datetime
    duration_ms: int = 0

    @property
    def seconds(self) -> float:
        return self.duration_ms / 1000.0


def load_config(path: Path) -> dict:
    if not path.exists():
        example = path.with_name("config.example.json")
        raise SystemExit(
            f"缺少 {path.name}\n"
            f"请先复制模板：cp {example.name} {path.name}\n"
            f"再填入你自己的凭据（config.json 已被 .gitignore 忽略，不会提交）"
        )
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def probe_duration_ms(path: Path) -> int:
    """
    没有 .meta 时（网盘上只存了 aac），直接读音频容器拿时长。
    av 的 duration 单位是微秒。
    """
    try:
        import av
        import avcompat  # noqa: F401

        with av.open(str(path)) as c:
            if c.duration:
                return int(c.duration / 1000)
    except Exception:
        pass
    return 0


def parse_clip(path: Path) -> Clip | None:
    m = ID_RE.search(path.name)
    if not m:
        return None
    d, t, _rand = m.groups()
    try:
        start = datetime.strptime(d + t, "%Y%m%d%H%M%S")
    except ValueError:
        return None

    duration = 0
    meta = path.with_suffix(".meta")
    if meta.exists():
        for line in meta.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.startswith("durationMs="):
                try:
                    duration = int(line.split("=", 1)[1])
                except ValueError:
                    pass
    if duration == 0:
        duration = probe_duration_ms(path)

    return Clip(path=path, clip_id=path.stem, start=start, duration_ms=duration)


def collect_local(src: str) -> list[Clip]:
    root = Path(src)
    clips = [c for p in sorted(root.rglob("*.aac")) if (c := parse_clip(p))]
    return sorted(clips, key=lambda c: c.start)


def collect_pan(cfg: dict, work_dir: Path, date_filter: str | None = None) -> list[Clip]:
    from baidu_pan import BaiduPan

    pan = BaiduPan(cfg["baidu"]["cookie_file"])
    remote_root = cfg["baidu"]["remote_dir"].rstrip("/")
    local_root = work_dir / "download"
    local_root.mkdir(parents=True, exist_ok=True)

    days = [date_filter] if date_filter else None
    if not days:
        days = [
            it["server_filename"]
            for it in pan.list_dir(remote_root)
            if it.get("isdir")
        ]
        # 网盘目录按日期命名，只处理形如 2026-11-06 的
        days = [d for d in days if re.fullmatch(r"\d{4}-\d{2}-\d{2}", d)]

    clips: list[Clip] = []
    for day in sorted(days):
        remote_day = f"{remote_root}/{day}"
        try:
            items = pan.list_dir(remote_day)
        except Exception as e:
            print(f"  [跳过] {remote_day}: {e}")
            continue

        local_day = local_root / day
        local_day.mkdir(parents=True, exist_ok=True)

        for it in items:
            name = it.get("server_filename", "")
            if not name.endswith(".aac"):
                continue
            local_file = local_day / name
            if not local_file.exists():
                print(f"  下载 {day}/{name} ...", end="", flush=True)
                try:
                    pan.download(f"{remote_day}/{name}", str(local_file))
                    print(" ok")
                except Exception as e:
                    print(f" 失败: {e}")
                    continue
            c = parse_clip(local_file)
            if c:
                clips.append(c)

    return sorted(clips, key=lambda c: c.start)


def load_cached(cache_dir: Path, clip_id: str) -> list[asr_mod.Segment] | None:
    f = cache_dir / f"{clip_id}.json"
    if not f.exists():
        return None
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
        return [asr_mod.Segment(s["start"], s["end"], s["text"]) for s in data]
    except Exception:
        return None


def save_cached(cache_dir: Path, clip_id: str, segs: list[asr_mod.Segment]) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    (cache_dir / f"{clip_id}.json").write_text(
        json.dumps([s.__dict__ for s in segs], ensure_ascii=False, indent=1),
        encoding="utf-8",
    )


def build_timeline(
    clips: list[Clip],
    transcriber,
    cache_dir: Path,
) -> tuple[list[tuple[datetime, str]], int, int]:
    """
    返回 (时间线, 命中缓存数, 实际转写数)。

    转写结果按片段 id 落缓存，重跑不再调 API —— 否则每次调参都要重花钱。
    """
    lines: list[tuple[datetime, str]] = []
    hits = news = 0

    print(f"共 {len(clips)} 片")
    for i, clip in enumerate(clips, 1):
        segs = load_cached(cache_dir, clip.clip_id)
        if segs is not None:
            hits += 1
        else:
            print(f"  [{i}/{len(clips)}] {clip.clip_id} ...", end="", flush=True)
            try:
                segs = transcriber.transcribe(str(clip.path))
            except Exception as e:
                print(f" 失败: {e}")
                continue
            save_cached(cache_dir, clip.clip_id, segs)
            news += 1
            print(f" {len(segs)} 句" if segs else " 空")

        for s in segs:
            lines.append((clip.start + timedelta(seconds=s.start), s.text))

    if hits:
        print(f"复用缓存 {hits} 片，新转写 {news} 片")

    lines.sort(key=lambda x: x[0])
    return lines, hits, news


def normalize(text: str) -> str:
    """Whisper 中文偶尔吐繁体，统一转简体，否则检索和阅读都别扭。"""
    try:
        from zhconv import convert

        return convert(text, "zh-cn")
    except Exception:
        return text


_PUNCT_ONLY = re.compile(r"^[\s\W_]+$", re.UNICODE)


def is_punct_only(text: str) -> bool:
    """剔除只有一个句号/省略号这类纯标点输出。"""
    return bool(_PUNCT_ONLY.match(text.strip()))


def collapse_repeats(lines: list[tuple[datetime, str]], threshold: int = 3):
    """
    连续同一句出现 >= threshold 次，判定为重复幻觉，只保留第一条。

    Whisper 在纯噪声段会发生"卡带"，例如把一句无意义的话重复七八遍，
    这类内容 compression_ratio 有时拦不住，必须在句子层面兜一层。
    """
    out: list[tuple[datetime, str]] = []
    i, n = 0, len(lines)
    while i < n:
        j = i
        while j + 1 < n and lines[j + 1][1] == lines[i][1]:
            j += 1
        run = j - i + 1
        out.append(lines[i])
        if run < threshold:
            out.extend(lines[i + 1 : j + 1])
        i = j + 1
    return out


def render_markdown(day: str, clips: list[Clip], lines: list[tuple[datetime, str]]) -> str:
    total = sum(c.seconds for c in clips)
    hours, rem = divmod(int(total), 3600)
    mins = rem // 60

    out = [f"# {day} 录音纪要", ""]
    out.append("## 一、概览")
    out.append("")
    out.append(f"- 片段数：{len(clips)}")
    out.append(f"- 录音总时长：{hours} 小时 {mins} 分" if hours else f"- 录音总时长：{mins} 分")
    if clips:
        out.append(f"- 时间范围：{clips[0].start:%H:%M:%S} ~ {clips[-1].start:%H:%M:%S}")
    out.append(f"- 有效语句：{len(lines)} 句")
    out.append("")
    out.append("## 二、时间线")
    out.append("")

    last_hour = None
    for ts, text in lines:
        h = ts.strftime("%Y-%m-%d %H")
        if h != last_hour:
            out.append(f"### {ts:%H}:00")
            out.append("")
            last_hour = h
        out.append(f"**{ts:%H:%M:%S}**　{text}")
        out.append("")

    out.append("---")
    out.append("")
    out.append(f"*由 AlwaysRec 后处理管线生成 · {datetime.now():%Y-%m-%d %H:%M}*")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--local", help="处理本地目录")
    ap.add_argument("--pan", action="store_true", help="从百度网盘拉取")
    ap.add_argument("--date", help="只处理某一天，格式 2026-11-06")
    ap.add_argument("--config", default=str(HERE / "config.json"))
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 片，用于试跑")
    args = ap.parse_args()

    cfg = load_config(Path(args.config))
    work_dir = Path(cfg["work_dir"]).resolve()
    work_dir.mkdir(parents=True, exist_ok=True)
    out_dir = Path(cfg["output_dir"]).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.local:
        clips = collect_local(args.local)
    elif args.pan:
        clips = collect_pan(cfg, work_dir, args.date)
    else:
        ap.error("需要 --local <目录> 或 --pan")

    if not clips:
        print("没有找到可处理的录音片段")
        return 1

    if args.limit:
        clips = clips[: args.limit]

    print(f"共 {len(clips)} 片，总时长 {sum(c.seconds for c in clips)/60:.1f} 分钟")

    cache_dir = work_dir / "transcripts"
    transcriber = asr_mod.build(cfg["asr"])
    lines, hits, news = build_timeline(clips, transcriber, cache_dir)

    raw_count = len(lines)
    lines = [(ts, normalize(t)) for ts, t in lines]
    lines = [(ts, t) for ts, t in lines if not is_punct_only(t)]
    lines = collapse_repeats(lines)
    if raw_count != len(lines):
        print(f"去除重复幻觉 {raw_count - len(lines)} 条")

    dropped = getattr(transcriber, "dropped", [])
    if dropped:
        print(f"\n已过滤 {len(dropped)} 条幻觉/噪声：")
        for reason, snippet in dropped[:12]:
            print(f"   [{reason}] {snippet}")

    # 按天拆分输出
    by_day: dict[str, list] = {}
    for ts, text in lines:
        by_day.setdefault(ts.strftime("%Y-%m-%d"), []).append((ts, text))

    for day, day_lines in sorted(by_day.items()):
        day_clips = [c for c in clips if c.start.strftime("%Y-%m-%d") == day]
        md = render_markdown(day, day_clips, day_lines)
        target = out_dir / f"{day}.md"
        target.write_text(md, encoding="utf-8")
        print(f"\n已生成 {target}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
