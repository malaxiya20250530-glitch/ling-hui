"""音频工具 — 录音和播放（Termux 环境）"""

import os
import subprocess
import tempfile
import time


def record_audio(output_path: str = None,
                 duration_sec: int = 10,
                 sample_rate: int = 16000) -> str:
    """使用 termux-microphone-record 录制音频，返回文件路径"""
    if output_path is None:
        output_path = os.path.join(tempfile.gettempdir(),
                                   f"lh_record_{int(time.time())}.wav")

    cmd = [
        "termux-microphone-record",
        "-f", output_path,
        "-l", str(duration_sec),
        "-r", str(sample_rate),
        "-b", "16",
        "-c", "1",
    ]
    try:
        subprocess.run(cmd, check=True, timeout=duration_sec + 5,
                       capture_output=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        raise RuntimeError(
            "录音失败：请安装 termux-api (pkg install termux-api)"
        )

    if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
        raise RuntimeError(f"录音文件为空: {output_path}")

    return output_path


def play_audio(file_path: str):
    """使用 termux-media-player 播放音频文件"""
    cmd = ["termux-media-player", "play", file_path]
    try:
        subprocess.run(cmd, check=True, timeout=60, capture_output=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        # 回退：使用 Python 内置方式（如果安装了 ffplay）
        try:
            subprocess.run(["ffplay", "-nodisp", "-autoexit", file_path],
                          check=True, timeout=60, capture_output=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            print(f"[音频] 无法播放: {file_path}")


def record_until_silence(output_path: str = None,
                         silence_sec: float = 2.0,
                         max_sec: int = 15,
                         sample_rate: int = 16000) -> str:
    """持续录音直到检测到静默或超时，返回文件路径"""
    # 简化实现：录制固定时长，后续版本加 VAD
    actual_dur = max_sec
    try:
        # 先尝试短录制看看用户说了什么
        actual_dur = min(max_sec, 10)
    except Exception:
        pass

    return record_audio(output_path=output_path,
                        duration_sec=actual_dur,
                        sample_rate=sample_rate)
