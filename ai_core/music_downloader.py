"""音乐搜索与下载 — 通过 yt-dlp 搜歌 + 自动下载为 mp3"""

import json
import os
import subprocess
import threading
import time
from pathlib import Path
import re

MUSIC_DIR = Path.home() / "ling-hui" / "music_downloads"
MUSIC_DIR.mkdir(parents=True, exist_ok=True)

_download_status = {}  # task_id -> {status, progress, file}


def search_music(query: str, limit: int = 8) -> list[dict]:
    """搜索歌曲，返回 [{"title":..., "url":..., "duration":..., "id":...}, ...]"""
    try:
        result = subprocess.run(
            ["yt-dlp", f"ytsearch{limit}:{query}", "--dump-json",
             "--no-playlist", "--flat-playlist", "--skip-download"],
            capture_output=True, text=True, timeout=30,
            env={**os.environ, "PATH": os.environ.get("PATH", "")},
        )
        if result.returncode != 0:
            return []

        songs = []
        seen = set()
        for line in result.stdout.strip().split("\n"):
            if not line:
                continue
            try:
                info = json.loads(line)
                vid = info.get("id", "")
                if vid in seen:
                    continue
                seen.add(vid)
                songs.append({
                    "id": vid,
                    "title": info.get("title", "未知"),
                    "url": info.get("webpage_url", info.get("url", "")),
                    "duration": info.get("duration_string", info.get("duration", "")),
                    "uploader": info.get("uploader", info.get("channel", "")),
                })
            except json.JSONDecodeError:
                continue

        return songs
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as e:
        return [{"error": str(e)}]


def download_song(url: str, task_id: str = None) -> dict:
    """下载歌曲为 mp3，返回 {"status":"ok"|"error", "file":str, "title":str}"""
    if task_id is None:
        task_id = str(int(time.time()))

    _download_status[task_id] = {"status": "downloading", "progress": 0, "file": ""}

    def _run():
        try:
            result = subprocess.run(
                ["yt-dlp", "-x", "--audio-format", "mp3",
                 "--audio-quality", "0",
                 "-o", f"{MUSIC_DIR}/%(title)s.%(ext)s",
                 "--print", "filename",
                 "--no-playlist", url],
                capture_output=True, text=True, timeout=300,
                cwd=str(MUSIC_DIR),
                env={**os.environ, "PATH": os.environ.get("PATH", "")},
            )

            if result.returncode == 0:
                filename = result.stdout.strip().split("\n")[-1]
                if not filename or not os.path.exists(filename):
                    mp3s = sorted(MUSIC_DIR.glob("*.mp3"), key=lambda p: p.stat().st_mtime, reverse=True)
                    filename = str(mp3s[0]) if mp3s else ""

                # 抓取歌词
                lyrics_file = _fetch_lyrics(url, filename)

                _download_status[task_id] = {
                    "status": "ok",
                    "progress": 100,
                    "file": filename,
                    "title": Path(filename).stem if filename else "未知",
                    "lyrics": lyrics_file,
                }
            else:
                err = result.stderr.strip()[-200:] if result.stderr else "下载失败"
                _download_status[task_id] = {"status": "error", "progress": 0, "file": "", "error": err}

        except subprocess.TimeoutExpired:
            _download_status[task_id] = {"status": "error", "progress": 0, "file": "", "error": "下载超时"}
        except Exception as e:
            _download_status[task_id] = {"status": "error", "progress": 0, "file": "", "error": str(e)}

    t = threading.Thread(target=_run, daemon=True)
    t.start()
    return {"task_id": task_id, "status": "downloading"}


def get_download_status(task_id: str) -> dict:
    """查询下载进度"""
    return _download_status.get(task_id, {"status": "not_found"})


# ── 歌词 ──────────────────────────────────────────

def _fetch_lyrics(video_url: str, mp3_path: str) -> str | None:
    """下载歌曲时同步抓取歌词，保存为同名的 .lrc 文件"""
    lrc_path = Path(mp3_path).with_suffix(".lrc")
    if lrc_path.exists():
        return str(lrc_path)

    try:
        result = subprocess.run(
            ["yt-dlp", "--write-subs", "--sub-langs", "zh-CN,zh,en",
             "--write-auto-subs", "--convert-subs", "lrc",
             "--skip-download", "-o", str(Path(mp3_path).with_suffix("")),
             video_url],
            capture_output=True, text=True, timeout=60,
            env={**os.environ, "PATH": os.environ.get("PATH", "")},
        )
        # 找生成的 lrc 文件
        for ext in [".zh-CN.lrc", ".zh.lrc", ".en.lrc", ".lrc"]:
            candidate = Path(mp3_path).with_suffix(ext)
            if candidate.exists():
                # 重命名为标准 .lrc
                if candidate.suffix != ".lrc":
                    candidate.rename(lrc_path)
                return str(lrc_path)

        # 尝试从视频描述提取歌词
        lyrics_text = _extract_lyrics_from_info(video_url)
        if lyrics_text:
            lrc_path.write_text(lyrics_text, encoding="utf-8")
            return str(lrc_path)

    except Exception:
        pass

    return None


def _extract_lyrics_from_info(video_url: str) -> str | None:
    """从视频描述/元数据中提取歌词文本"""
    try:
        result = subprocess.run(
            ["yt-dlp", "--dump-json", "--skip-download", video_url],
            capture_output=True, text=True, timeout=30,
            env={**os.environ, "PATH": os.environ.get("PATH", "")},
        )
        if result.returncode == 0 and result.stdout.strip():
            info = json.loads(result.stdout.strip())
            desc = info.get("description", "")
            # 简单检测：描述中包含「歌词」或常见歌词模式
            if "歌词" in desc or "Lyrics" in desc or "♪" in desc or "♫" in desc:
                return _clean_lyrics(desc)
    except Exception:
        pass
    return None


def _clean_lyrics(text: str) -> str:
    """清洗歌词文本，去掉 URL 和空行"""
    lines = []
    for line in text.split("\n"):
        line = line.strip()
        if not line or "http" in line or "youtube" in line.lower():
            continue
        lines.append(line)
    return "\n".join(lines)


def parse_lrc(lrc_text: str) -> list[dict]:
    """解析 LRC 格式歌词为 [{time_sec, text}, ...]

    支持 [mm:ss.xx] 格式的时间标签"""
    result = []
    pattern = re.compile(r"\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\](.*)")
    for line in lrc_text.split("\n"):
        match = pattern.match(line.strip())
        if match:
            minutes = int(match.group(1))
            seconds = int(match.group(2))
            millis_str = match.group(3)
            millis = int(millis_str) * 10 if millis_str else 0
            total_sec = minutes * 60 + seconds + millis / 1000.0
            text = match.group(4).strip()
            if text:
                result.append({"time": total_sec, "text": text})
    result.sort(key=lambda x: x["time"])
    return result


def get_lyrics(mp3_path: str) -> dict:
    """获取歌曲的歌词，返回 {format:'lrc'|'text', lines:[...], raw:str}"""
    lrc_path = Path(mp3_path).with_suffix(".lrc")
    txt_path = Path(mp3_path).with_suffix(".txt")

    for p in [lrc_path, txt_path]:
        if p.exists():
            try:
                raw = p.read_text(encoding="utf-8")
                if raw.startswith("[") and "]" in raw[:10]:
                    parsed = parse_lrc(raw)
                    return {"format": "lrc" if parsed else "text",
                            "lines": parsed if parsed else [{"time": 0, "text": l} for l in raw.split("\n") if l.strip()],
                            "raw": raw}
                return {"format": "text",
                        "lines": [{"time": i * 2, "text": l} for i, l in enumerate(raw.split("\n")) if l.strip()],
                        "raw": raw}
            except Exception:
                pass
    return {"format": "none", "lines": [], "raw": ""}


def list_downloaded() -> list[dict]:
    """列出已下载的音乐文件"""
    songs = []
    for f in sorted(MUSIC_DIR.glob("*.mp3"), key=lambda p: p.stat().st_mtime, reverse=True):
        has_lrc = f.with_suffix(".lrc").exists() or f.with_suffix(".txt").exists()
        songs.append({
            "title": f.stem,
            "file": f.name,
            "path": str(f),
            "size": f.stat().st_size,
            "has_lyrics": has_lrc,
        })
    return songs


def get_music_file_url(filename: str) -> str:
    """获取音乐文件路径"""
    fp = MUSIC_DIR / filename
    return str(fp) if fp.exists() else ""
