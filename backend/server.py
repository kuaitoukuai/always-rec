# -*- coding: utf-8 -*-
"""
录音看板 —— 电脑端监控页
=========================
    python server.py            # 默认 http://127.0.0.1:8770
    python server.py --port 9000 --no-sync

它做四件事：
  1. 后台每 5 分钟从百度网盘同步：录音片段、转写文本、设备心跳
  2. 对每个有内容的小时生成 AI 概要（data/hourly/）
  3. 生成当天总览概要（data/daily/）
  4. 提供一个网页：设备状态、采集/解析统计、最近 5 条实时、时段分布、逐条文字

判断设备是否还活着看心跳：App 每 10 分钟写 /录音记录/_状态/device.json。
"""
from __future__ import annotations

import argparse
import json
import re
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

from flask import Flask, jsonify, render_template_string, request

import asr as asr_mod
import llm
from baidu_pan import BaiduPan
from pipeline import build_timeline, collect_pan, load_config, normalize, probe_duration_ms

HERE = Path(__file__).resolve().parent
CFG = load_config(HERE / "config.json")
WORK = Path(CFG["work_dir"]).resolve()
TRANS_DIR = WORK / "transcripts"
HOURLY_DIR = WORK / "hourly"
DAILY_DIR = WORK / "daily"
STATUS_DIR = WORK / "status"
DL_DIR = WORK / "download"

for d in (WORK, TRANS_DIR, HOURLY_DIR, DAILY_DIR, STATUS_DIR):
    d.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)

STALE_MIN = 25
OFFLINE_MIN = 60
RECENT_N = 5


# --------------------------------------------------------------------------
# 数据
# --------------------------------------------------------------------------

@dataclass
class Line:
    ts: datetime
    text: str
    device: str = ""  # 网盘子目录名；空字符串 = 旧布局（所有设备混在一起）


def clip_time(clip_id: str) -> datetime | None:
    try:
        p = clip_id.split("_")
        return datetime.strptime(p[0] + p[1], "%Y%m%d%H%M%S")
    except Exception:
        return None


NOISE_WORDS = {
    "hmm", "hm", "uh", "um", "umm", "er", "ah", "oh",
    "cough", "coughs", "coughing", "ahem", "sigh", "sighs",
    "laughter", "laughs", "laugh", "noise", "background noise",
    "silence", "music", "applause", "breath", "breathing", "sneeze",
}


def is_junk(text: str) -> bool:
    """模型会把咳嗽、叹气标成英文词，这些不该出现在看板上。"""
    s = text.strip().strip(".。,，!！?？、 ")
    if not s:
        return True
    low = s.lower()
    if low in NOISE_WORDS:
        return True
    if len(low) <= 5 and low.isascii() and not any(c.isdigit() for c in low):
        return True
    return False


def load_lines(date: str) -> list[Line]:
    """
    转写缓存有两种位置：
        data/transcripts/<片段>.json          老版本，不分设备
        data/transcripts/<设备>/<片段>.json   新版本
    统一读进来，并把设备名带在每一行上。
    """
    files = list(TRANS_DIR.glob("*.json")) + list(TRANS_DIR.glob("*/*.json"))
    out: list[Line] = []
    for f in files:
        device = "" if f.parent == TRANS_DIR else f.parent.name
        start = clip_time(f.stem)
        if not start or start.strftime("%Y-%m-%d") != date:
            continue
        try:
            segs = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        for s in segs:
            text = normalize((s.get("text") or "").strip())
            if text and not is_junk(text):
                out.append(Line(start + timedelta(seconds=s.get("start", 0)), text, device))
    out.sort(key=lambda x: x.ts)
    return out


LEGACY_HB = "device.json"


def read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def load_heartbeats() -> tuple[dict[str, dict], dict | None]:
    """
    返回 ({设备名: 心跳}, 旧版心跳)。

    判定规则：v0.2+ 的心跳里带 "device" 字段，直接按它归类；
    老版本没有这个字段（文件名可能是 device.json，也可能带时间戳），
    全部归到"旧数据"一台设备里，取 ts 最新的那条。
    """
    devices: dict[str, dict] = {}
    legacy_pool: list[dict] = []
    if STATUS_DIR.exists():
        for f in STATUS_DIR.glob("*.json"):
            d = read_json(f)
            if not d:
                continue
            name = (d.get("device") or "").strip()
            if name:
                devices[name] = d
            else:
                legacy_pool.append(d)
    legacy = max(legacy_pool, key=lambda x: x.get("ts", 0), default=None)
    return devices, legacy


def display_names(devices: dict[str, dict], legacy: dict | None) -> dict[str, str]:
    """目录名 → 给人看的名字：优先自定义名，其次机型，最后用目录名。"""
    names: dict[str, str] = {}
    for dev, hb in devices.items():
        names[dev] = (hb.get("alias") or "").strip() or (hb.get("model") or "").strip() or dev

    # 旧布局没有设备名，只有机型。加「旧数据」后缀，避免和升级后同名设备混淆
    legacy_model = ((legacy or {}).get("model") or "").strip()
    names[""] = f"{legacy_model} · 旧数据" if legacy_model else "旧数据"
    return names


def day_dirs(date: str) -> list[tuple[str, Path]]:
    """当天有音频的目录：[(设备名, 本地目录)]，兼容旧布局。"""
    out: list[tuple[str, Path]] = []
    legacy = DL_DIR / date
    if legacy.is_dir():
        out.append(("", legacy))
    if DL_DIR.exists():
        for dev_dir in DL_DIR.iterdir():
            if not dev_dir.is_dir():
                continue
            d = dev_dir / date
            if d.is_dir():
                out.append((dev_dir.name, d))
    return out


def _parsed_marker(device: str, clip_id: str) -> bool:
    if device:
        return (TRANS_DIR / device / f"{clip_id}.json").exists()
    return (TRANS_DIR / f"{clip_id}.json").exists()


def collect_stats(date: str, device: str | None = None) -> dict:
    """采集与解析的进度统计，含按设备拆分。"""
    per_device = []
    clips_total = parsed_total = 0
    sec_total = 0.0
    bytes_total = 0

    for dev, d in day_dirs(date):
        if device is not None and dev != device:
            continue
        clips = sorted(d.glob("*.aac"))
        if not clips:
            continue
        sec = 0.0
        nbytes = 0
        for c in clips:
            nbytes += c.stat().st_size
            sec += probe_duration_ms(c) / 1000.0
        parsed = sum(1 for c in clips if _parsed_marker(dev, c.stem))
        per_device.append({
            "device": dev,
            "clips": len(clips),
            "parsed": parsed,
            "seconds": sec,
            "bytes": nbytes,
            "minutes": int(sec // 60),
            "mb": round(nbytes / 1024 / 1024, 1),
            "rate": round(parsed / len(clips) * 100, 1) if clips else 0,
        })
        clips_total += len(clips)
        parsed_total += parsed
        sec_total += sec
        bytes_total += nbytes

    return {
        "clips": clips_total,
        "parsed": parsed_total,
        "seconds": sec_total,
        "bytes": bytes_total,
        "rate": (parsed_total / clips_total * 100) if clips_total else 0,
        "devices": per_device,
    }


def fmt_seconds(sec: float) -> str:
    sec = int(sec)
    h, rem = divmod(sec, 3600)
    m = rem // 60
    if h:
        return f"{h}<span>小时</span>{m}<span>分</span>"
    return f"{m}<span>分</span>"


def fmt_bytes(n: int) -> str:
    if n >= 1024 * 1024:
        return f"{n / 1024 / 1024:.1f}<span>MB</span>"
    return f"{n / 1024:.0f}<span>KB</span>"


# --------------------------------------------------------------------------
# 概要生成
# --------------------------------------------------------------------------

def newest_mtime(date: str, hour: int | None = None) -> float:
    best = 0.0
    for p in list(TRANS_DIR.glob("*.json")) + list(TRANS_DIR.glob("*/*.json")):
        t = clip_time(p.stem)
        if not t or t.strftime("%Y-%m-%d") != date:
            continue
        if hour is not None and t.hour != hour:
            continue
        best = max(best, p.stat().st_mtime)
    return best


def ensure_hourly(date: str, hour: int, lines: list[Line]) -> str | None:
    f = HOURLY_DIR / f"{date}_{hour:02d}.md"
    joined = "\n".join(l.text for l in lines)
    if len(joined.strip()) < 20:
        return None
    if f.exists() and f.stat().st_mtime >= newest_mtime(date, hour):
        return f.read_text(encoding="utf-8")

    prompt = (
        f"下面是 {date} {hour:02d}:00-{hour:02d}:59 这一小时的语音转写片段。"
        "请用 3-5 句话概括这一小时实际发生了什么，要求：\n"
        "1. 只保留有信息量的内容，忽略闲聊、电视、短视频、背景音\n"
        "2. 如果有明确的待办、约定、决策，单独点出来\n"
        "3. 如果整段都没有实质内容，只回「无有效内容」四个字\n"
        "4. 不要加标题，直接给正文\n\n"
        f"转写内容：\n{joined[:12000]}"
    )
    summary = llm.chat(prompt)
    if not summary:
        return None
    f.write_text(summary, encoding="utf-8")
    return summary


def ensure_daily(date: str, lines: list[Line]) -> str | None:
    f = DAILY_DIR / f"{date}.md"
    joined = "\n".join(f"{l.ts:%H:%M} {l.text}" for l in lines)
    if len(joined.strip()) < 30:
        return None
    if f.exists() and f.stat().st_mtime >= newest_mtime(date):
        return f.read_text(encoding="utf-8")

    prompt = (
        f"下面是 {date} 全天的语音转写时间线。请整理成一份当日纪要，格式：\n"
        "1. 先用 3-5 条要点概括今天主要发生了什么（只写有实质信息的）\n"
        "2. 再列「待办 / 需跟进」，没有就写「无」\n"
        "3. 忽略闲聊、电视、短视频、背景音\n"
        "4. 总长度 400 字以内，不要用 Markdown 星号\n\n"
        f"时间线：\n{joined[:20000]}"
    )
    summary = llm.chat(prompt)
    if not summary:
        return None
    f.write_text(summary, encoding="utf-8")
    return summary


# --------------------------------------------------------------------------
# 网盘同步
# --------------------------------------------------------------------------

def recent_dates(days: int = 2) -> list[str]:
    """最近 N 天（含今天）。跨天补传的数据靠这个窗口自动捡回来。"""
    now = datetime.now()
    return [(now - timedelta(days=i)).strftime("%Y-%m-%d") for i in range(max(1, days))]


def sync_once(dates: str | list[str] | None = None) -> dict:
    if isinstance(dates, str):
        dates = [dates]
    elif not dates:
        dates = recent_dates(1)

    result = {"clips": 0, "transcribed": 0, "devices": 0, "days": len(dates), "error": None}
    try:
        pan = BaiduPan(CFG["baidu"]["cookie_file"])
        remote_status = f'{CFG["baidu"]["remote_dir"].rstrip("/")}/_状态'

        # 每台设备一个心跳文件，全部拉下来
        try:
            for it in pan.list_dir(remote_status):
                name = it.get("server_filename", "")
                if not name.endswith(".json"):
                    continue
                try:
                    pan.download(f"{remote_status}/{name}", str(STATUS_DIR / name))
                except Exception:
                    pass
        except Exception:
            pass

        clips = []
        for d in dates:
            clips.extend(collect_pan(CFG, WORK, d))

        result["clips"] = len(clips)
        result["devices"] = len({c.device for c in clips})
        if clips:
            transcriber = asr_mod.build(CFG["asr"])
            _, _, news = build_timeline(clips, transcriber, TRANS_DIR)
            result["transcribed"] = news
    except Exception as e:
        result["error"] = str(e)[:200]
    return result


def sync_loop(interval: int = 300, days: int = 2) -> None:
    while True:
        try:
            r = sync_once(recent_dates(days))
            (WORK / "last_sync.txt").write_text(
                f"{datetime.now():%H:%M:%S} · {r['clips']} 段 · 新增 {r['transcribed']}"
                + (f" · {r['error']}" if r["error"] else ""),
                encoding="utf-8",
            )
            print(f"[sync] {datetime.now():%H:%M:%S} {r}", flush=True)
        except Exception as e:
            print(f"[sync] 异常: {e}", flush=True)
        time.sleep(interval)


# --------------------------------------------------------------------------
# 页面
# --------------------------------------------------------------------------

PAGE = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>录音看板 · {{ date }}</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#F4F6F9;color:#1a1a1a;
     font-family:system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
     font-size:14px;line-height:1.6;-webkit-font-smoothing:antialiased}
.wrap{max-width:960px;margin:0 auto;padding:28px 20px 80px}
h1{font-size:21px;font-weight:500;margin:0 0 6px;letter-spacing:-.2px}
.sub{color:#8A9099;font-size:13px;margin-bottom:18px}
.card{background:#fff;border-radius:12px;padding:18px 20px;margin-bottom:14px;
      border:1px solid #E9EDF2}
.card-title{font-size:13px;color:#6B7280;margin-bottom:12px;letter-spacing:.2px}

.status{display:flex;align-items:center;gap:10px;padding:14px 18px;
        background:#fff;border-radius:12px;border:1px solid #E9EDF2;margin-bottom:14px}
.dot{width:9px;height:9px;border-radius:50%;flex:0 0 auto}
.ok{background:#1D9E75;box-shadow:0 0 0 3px rgba(29,158,117,.15)}
.warn{background:#EF9F27;box-shadow:0 0 0 3px rgba(239,159,39,.15)}
.bad{background:#E24B4A;box-shadow:0 0 0 3px rgba(226,75,74,.15)}
.status b{font-weight:500}
.status .meta{color:#8A9099;font-size:12px;margin-left:auto;text-align:right}
.tag{display:inline-block;font-size:11px;color:#5A6472;background:#EEF2F7;
     border-radius:5px;padding:0 6px;margin-right:7px;line-height:18px;vertical-align:1px}
.stat-row{display:flex;align-items:baseline;gap:12px;padding:9px 0;border-bottom:1px solid #F3F5F8}
.stat-row:last-child{border-bottom:none}
.stat-row .nm{flex:0 0 140px;font-weight:500;font-size:13px}
.stat-row .mt{color:#8A9099;font-size:12px}
.stat-row .bar{flex:1;height:4px;background:#EEF1F5;border-radius:2px;overflow:hidden;min-width:40px}
.stat-row .bar i{display:block;height:100%;background:#1D9E75}

.grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-bottom:14px}
.metric{background:#fff;border:1px solid #E9EDF2;border-radius:12px;padding:14px 16px}
.metric .v{font-size:22px;font-weight:500;letter-spacing:-.5px;line-height:1.25}
.metric .v span{font-size:12px;font-weight:400;color:#8A9099;margin-left:2px}
.metric .k{font-size:12px;color:#8A9099;margin-top:2px}
.metric .bar{height:3px;background:#EEF1F5;border-radius:2px;margin-top:8px;overflow:hidden}
.metric .bar i{display:block;height:100%;background:#1D9E75;border-radius:2px}

.recent .row{display:flex;gap:12px;padding:7px 0;border-bottom:1px solid #F3F5F8;font-size:13px}
.recent .row:last-child{border-bottom:none}
.recent .t{color:#9AA0A8;font-variant-numeric:tabular-nums;flex:0 0 62px}
.recent .x{color:#333}
.live{display:inline-flex;align-items:center;gap:5px;color:#1D9E75;font-size:11px;margin-left:8px}
.live i{width:6px;height:6px;border-radius:50%;background:#1D9E75;animation:p 2s infinite}
@keyframes p{0%,100%{opacity:1}50%{opacity:.25}}

.bars .b{display:flex;align-items:center;gap:10px;margin-bottom:6px;font-size:12px}
.bars .h{flex:0 0 46px;color:#8A9099;font-variant-numeric:tabular-nums}
.bars .track{flex:1;height:16px;background:#F3F5F8;border-radius:4px;overflow:hidden}
.bars .fill{height:100%;background:#B5D4F4;border-radius:4px}
.bars .n{flex:0 0 54px;text-align:right;color:#8A9099}

.summary{color:#333;white-space:pre-wrap;font-size:14px}
.none{color:#A6ACB5;font-style:italic}

.hour-head{display:flex;align-items:baseline;gap:10px;margin-bottom:8px}
.hour-time{font-weight:500;font-size:15px}
.hour-count{color:#9AA0A8;font-size:12px}
details{margin-top:10px}
summary{cursor:pointer;color:#185FA5;font-size:13px;outline:none;user-select:none}
.line{display:flex;gap:12px;padding:4px 0;font-size:13px;color:#444}
.line .t{color:#A0A6AE;font-variant-numeric:tabular-nums;flex:0 0 62px}
.foot{color:#A6ACB5;font-size:12px;text-align:center;margin-top:24px}
@media(max-width:720px){.grid{grid-template-columns:repeat(2,1fr)}}
</style></head><body><div class="wrap">

<h1>{{ date }}　录音看板</h1>
<div class="sub">
  {% if days|length > 1 %}
    {% for d in days %}<a href="?date={{ d }}" style="color:{{ '#185FA5' if d == date else '#A6ACB5' }};text-decoration:none;margin-right:12px">{{ d }}</a>{% endfor %}
  {% else %}
    数据来自百度网盘「录音记录」，后台每 5 分钟自动同步
  {% endif %}
</div>

<div id="status-box">
{% for d in device_status %}
<div class="status">
  <span class="dot {{ d.cls }}"></span>
  <b>{{ d.name }}</b>
  <span class="meta">{{ d.meta }}</span>
</div>
{% endfor %}
{% if not device_status %}
<div class="status"><span class="dot bad"></span><b>还没有设备上报</b>
  <span class="meta">手机上点「开始录音」，10 分钟内会出现</span></div>
{% endif %}
</div>

<div class="grid">
  <div class="metric">
    <div class="v" id="m-clips">{{ stats.clips }}<span>段</span></div>
    <div class="k">采集片段</div>
  </div>
  <div class="metric">
    <div class="v">{{ duration_html|safe }}</div>
    <div class="k">录音时长</div>
  </div>
  <div class="metric">
    <div class="v" id="m-parsed">{{ stats.parsed }}<span>/{{ stats.clips }}</span></div>
    <div class="k">已解析</div>
    <div class="bar"><i id="m-bar" style="width:{{ stats.rate }}%"></i></div>
  </div>
  <div class="metric">
    <div class="v" id="m-lines">{{ total_lines }}<span>条</span></div>
    <div class="k">对话条数</div>
  </div>
  <div class="metric">
    <div class="v">{{ size_html|safe }}</div>
    <div class="k">音频体积</div>
  </div>
</div>

{% if stats.devices %}
<div class="card">
  <div class="card-title">按设备拆分{% if multi_device %}（{{ stats.devices|length }} 台）{% endif %}</div>
  {% for d in stats.devices %}
  <div class="stat-row">
    <span class="nm">{{ names.get(d.device, d.device or '旧数据') }}</span>
    <span class="mt">{{ d.clips }} 段 · {{ d.parsed }} 已解析 · {{ d.minutes }} 分 · {{ d.mb }} MB</span>
    <span class="bar"><i style="width:{{ d.rate }}%"></i></span>
  </div>
  {% endfor %}
</div>
{% endif %}

<div class="card recent">
  <div class="card-title">最后 {{ recent_n }} 条 <span class="live"><i></i>实时</span></div>
  <div id="recent">
    {% for l in recent %}
    <div class="row"><span class="t">{{ l.ts.strftime('%H:%M:%S') }}</span><span class="x">{% if multi_device %}<span class="tag">{{ names.get(l.device, l.device or '旧数据') }}</span>{% endif %}{{ l.text }}</span></div>
    {% endfor %}
    {% if not recent %}<div class="none">还没有内容</div>{% endif %}
  </div>
</div>

{% if daily %}
<div class="card">
  <div class="card-title">今日概要</div>
  <div class="summary">{{ daily }}</div>
</div>
{% endif %}

{% if bars %}
<div class="card bars">
  <div class="card-title">时段分布（每小时对话条数）</div>
  {% for b in bars %}
  <div class="b">
    <span class="h">{{ '%02d'|format(b.hour) }}:00</span>
    <span class="track"><i class="fill" style="width:{{ b.pct }}%;display:block;height:100%"></i></span>
    <span class="n">{{ b.count }} 条</span>
  </div>
  {% endfor %}
</div>
{% endif %}

{% for h in hours %}
<div class="card">
  <div class="hour-head">
    <span class="hour-time">{{ '%02d'|format(h.hour) }}:00 – {{ '%02d'|format(h.hour) }}:59</span>
    <span class="hour-count">{{ h.count }} 条{% if multi_device %} · {{ h.devices|join(' · ') }}{% endif %}</span>
  </div>
  <div class="summary {{ 'none' if not h.summary }}">
    {{ h.summary if h.summary else '无有效内容' }}
  </div>
  <details>
    <summary>展开原文（{{ h.lines|length }} 条）</summary>
    {% for l in h.lines %}
    <div class="line"><span class="t">{{ l.ts.strftime('%H:%M:%S') }}</span><span>{% if multi_device %}<span class="tag">{{ names.get(l.device, l.device or '旧数据') }}</span>{% endif %}{{ l.text }}</span></div>
    {% endfor %}
  </details>
</div>
{% endfor %}

{% if not hours %}
<div class="card none">这一天还没有同步到录音。确认手机在录音、连了 WiFi。</div>
{% endif %}

<div class="foot">每 10 秒自动刷新指标与最近记录 · 上次同步 <span id="ft">{{ last_sync or '尚未同步' }}</span></div>
</div>

<script>
var DATE = "{{ date }}";
var MULTI = {{ 'true' if multi_device else 'false' }};
function esc(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML;}
function tick(){
  fetch('/api/live?date=' + DATE).then(function(r){return r.json();}).then(function(d){
    var sb = document.getElementById('status-box');
    sb.innerHTML = d.devices.length
      ? d.devices.map(function(x){
          return '<div class="status"><span class="dot ' + x.cls + '"></span><b>' + esc(x.name) +
                 '</b><span class="meta">' + esc(x.meta) + '</span></div>';
        }).join('')
      : '<div class="status"><span class="dot bad"></span><b>还没有设备上报</b>' +
        '<span class="meta">手机上点「开始录音」，10 分钟内会出现</span></div>';

    document.getElementById('m-clips').innerHTML = d.clips + '<span>段</span>';
    document.getElementById('m-parsed').innerHTML = d.parsed + '<span>/' + d.clips + '</span>';
    document.getElementById('m-bar').style.width = d.rate + '%';
    document.getElementById('m-lines').innerHTML = d.lines + '<span>条</span>';

    var box = document.getElementById('recent');
    if (d.recent.length === 0) { box.innerHTML = '<div class="none">还没有内容</div>'; }
    else {
      box.innerHTML = d.recent.map(function(x){
        var tag = (MULTI && x.dev) ? '<span class="tag">' + esc(x.dev) + '</span>' : '';
        return '<div class="row"><span class="t">' + x.t + '</span><span class="x">' + tag + esc(x.text) + '</span></div>';
      }).join('');
    }
    if (d.last_sync) document.getElementById('ft').textContent = d.last_sync;
  }).catch(function(){});
}
setInterval(tick, 10000);
</script>
</body></html>"""


def status_of(hb: dict | None) -> tuple[str, str, str]:
    if not hb:
        return "bad", "设备未上报", "还没有收到任何心跳"
    ts = hb.get("ts", 0) / 1000
    age_min = (time.time() - ts) / 60
    bat = hb.get("battery", -1)
    pending = hb.get("pending", 0)
    rec = "录音中" if hb.get("recording") else "已停止"
    charge = " · 充电中" if hb.get("charging") else ""
    meta = f"最后心跳 {int(age_min)} 分钟前 · 电量 {bat}%{charge} · 待上传 {pending} 片 · {rec}"
    if age_min <= STALE_MIN:
        return "ok", "设备在线", meta
    if age_min <= OFFLINE_MIN:
        return "warn", "心跳延迟", meta
    return "bad", "设备可能已离线", meta


def all_days() -> list[str]:
    """所有有数据的日期，兼容「根目录直接是日期」的老布局。"""
    out: set[str] = set()
    if not DL_DIR.exists():
        return []
    for p in DL_DIR.iterdir():
        if not p.is_dir():
            continue
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", p.name):
            out.add(p.name)
            continue
        for q in p.iterdir():
            if q.is_dir() and re.fullmatch(r"\d{4}-\d{2}-\d{2}", q.name):
                out.add(q.name)
    return sorted(out, reverse=True)


def build_device_status(
    devices: dict[str, dict], legacy: dict | None, names: dict[str, str], date: str
) -> list[dict]:
    rows = []
    for dev, hb in devices.items():
        cls, _text, meta = status_of(hb)
        rows.append({"device": dev, "name": names.get(dev, dev), "cls": cls, "meta": meta})

    if legacy:
        cls, _text, meta = status_of(legacy)
        rows.append({"device": "", "name": names[""], "cls": cls, "meta": meta})

    # 有录音但没收到心跳的设备（刚装、或心跳还没传上来）
    for dev, _d in day_dirs(date):
        if dev and dev not in devices:
            rows.append({
                "device": dev,
                "name": names.get(dev, dev),
                "cls": "warn",
                "meta": "有录音但没收到心跳，检查通知栏是否还在",
            })
    return rows


@app.route("/")
def index():
    date = request.args.get("date") or datetime.now().strftime("%Y-%m-%d")
    lines = load_lines(date)
    stats = collect_stats(date)

    heartbeats, legacy = load_heartbeats()
    names = display_names(heartbeats, legacy)
    devices_today = {dev for dev, _ in day_dirs(date)}

    by_hour: dict[int, list[Line]] = {}
    for l in lines:
        by_hour.setdefault(l.ts.hour, []).append(l)

    hours = []
    for h, ls in sorted(by_hour.items(), reverse=True):
        s = ensure_hourly(date, h, ls)
        hours.append({
            "hour": h,
            "count": len(ls),
            "summary": None if (not s or s.strip() == "无有效内容") else s,
            # 原文按时间倒序，跟 App 端保持一致
            "lines": ls[::-1],
            "devices": sorted({names.get(l.device, l.device or names[""]) for l in ls}),
        })

    top = max((len(v) for v in by_hour.values()), default=1) or 1
    bars = [
        {"hour": h, "count": len(v), "pct": round(len(v) / top * 100)}
        for h, v in sorted(by_hour.items())
    ]

    ls_file = WORK / "last_sync.txt"
    last_sync = ls_file.read_text(encoding="utf-8").strip() if ls_file.exists() else None

    return render_template_string(
        PAGE,
        date=date,
        days=all_days(),
        hours=hours,
        bars=bars,
        recent=lines[-RECENT_N:][::-1],
        recent_n=RECENT_N,
        daily=ensure_daily(date, lines),
        total_lines=len(lines),
        stats=stats,
        names=names,
        multi_device=len(devices_today) > 1,
        device_status=build_device_status(heartbeats, legacy, names, date),
        duration_html=fmt_seconds(stats["seconds"]),
        size_html=fmt_bytes(stats["bytes"]),
        last_sync=last_sync,
    )


@app.route("/api/live")
def api_live():
    date = request.args.get("date") or datetime.now().strftime("%Y-%m-%d")
    lines = load_lines(date)
    stats = collect_stats(date)
    heartbeats, legacy = load_heartbeats()
    names = display_names(heartbeats, legacy)

    ls_file = WORK / "last_sync.txt"
    return jsonify({
        "devices": build_device_status(heartbeats, legacy, names, date),
        "clips": stats["clips"],
        "parsed": stats["parsed"],
        "rate": round(stats["rate"], 1),
        "lines": len(lines),
        "recent": [
            {
                "t": l.ts.strftime("%H:%M:%S"),
                "text": l.text,
                "dev": names.get(l.device, l.device or names[""]),
            }
            for l in lines[-RECENT_N:][::-1]
        ],
        "last_sync": ls_file.read_text(encoding="utf-8").strip() if ls_file.exists() else None,
    })


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8770)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--no-sync", action="store_true", help="只起页面，不同步网盘")
    ap.add_argument("--days", type=int, default=2, help="每轮回看最近几天，默认 2（今天+昨天）")
    args = ap.parse_args()

    if not args.no_sync:
        # 60 秒一次：本地同步只是列网盘目录，成本很低，但能把"手机刚传上去"
        # 到你看到页面的延迟压到一分钟内。
        # 回看 2 天：跨过午夜后，昨天最后一段也能自动补进来（评审报告 P1-5）。
        threading.Thread(target=lambda: sync_loop(60, args.days), daemon=True).start()

    print(f"\n  录音看板: http://{args.host}:{args.port}\n", flush=True)
    app.run(host=args.host, port=args.port, debug=False, use_reloader=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
