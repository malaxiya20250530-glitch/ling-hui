"""手机自动化操作 — 通过 termux-api 和 Android shell 执行底层操作"""

import json
import os
import subprocess
import time
from enum import Enum
from typing import Optional


class ActionResult(Enum):
    OK = "ok"
    FAILED = "failed"
    UNAVAILABLE = "unavailable"
    FORBIDDEN = "forbidden"


def _run_termux(*args, timeout: int = 10) -> tuple[bool, str]:
    """安全执行 termux 命令，返回 (成功, 输出)"""
    try:
        result = subprocess.run(
            list(args), capture_output=True, text=True, timeout=timeout
        )
        return result.returncode == 0, result.stdout.strip() or result.stderr.strip()
    except FileNotFoundError:
        return False, "termux-api 未安装 (pkg install termux-api)"
    except subprocess.TimeoutExpired:
        return False, "命令超时"


def _run_shell(cmd: str, timeout: int = 10) -> tuple[bool, str]:
    """执行 Android shell 命令"""
    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, timeout=timeout
        )
        return result.returncode == 0, result.stdout.strip() or result.stderr.strip()
    except subprocess.TimeoutExpired:
        return False, "命令超时"
    except Exception as e:
        return False, str(e)


# ── 应用操作 ──────────────────────────────────────────

def open_app(package_name: str) -> ActionResult:
    """通过包名打开 Android 应用"""
    # 尝试 termux-open (启动隐式intent)
    ok, out = _run_shell(
        f"am start -a android.intent.action.MAIN "
        f"-c android.intent.category.LAUNCHER "
        f"$(pm list packages {package_name} | head -1 | cut -d: -f2)/.MainActivity "
        f"2>/dev/null",
        timeout=5,
    )
    if ok:
        return ActionResult.OK

    # 回退：用 monkey 启动
    ok2, _ = _run_shell(
        f"monkey -p {package_name} -c android.intent.category.LAUNCHER 1 2>/dev/null",
        timeout=5,
    )
    return ActionResult.OK if ok2 else ActionResult.FAILED


def open_url(url: str) -> ActionResult:
    """打开 URL（浏览器或对应应用）"""
    ok, msg = _run_termux("termux-open-url", url)
    if not ok:
        ok2, _ = _run_shell(f"am start -a android.intent.action.VIEW -d '{url}'",
                           timeout=5)
        return ActionResult.OK if ok2 else ActionResult.FAILED
    return ActionResult.OK


def search_apps(keyword: str) -> list[str]:
    """搜索已安装应用"""
    ok, out = _run_shell(f"pm list packages 2>/dev/null | grep -i '{keyword}'",
                        timeout=5)
    if not ok or not out:
        return []
    return [line.replace("package:", "").strip() for line in out.split("\n") if line]


# ── 通知与提示 ────────────────────────────────────────

def send_notification(title: str, content: str = "",
                      sound: bool = True) -> ActionResult:
    """发送系统通知"""
    args = ["termux-notification", "-t", title]
    if content:
        args.extend(["-c", content])
    if not sound:
        args.append("--alert-once")
    ok, _ = _run_termux(*args)
    return ActionResult.OK if ok else ActionResult.UNAVAILABLE


def show_toast(text: str, short: bool = True) -> ActionResult:
    """弹出 Toast 提示"""
    # termux-toast 可能在不同版本中参数不同
    ok, _ = _run_termux("termux-toast", text)
    return ActionResult.OK if ok else ActionResult.UNAVAILABLE


# ── 剪贴板 ────────────────────────────────────────────

def clipboard_set(text: str) -> ActionResult:
    """设置剪贴板内容"""
    # termux-clipboard-set 从 stdin 读取
    try:
        result = subprocess.run(
            ["termux-clipboard-set"], input=text, capture_output=True,
            text=True, timeout=5
        )
        return ActionResult.OK if result.returncode == 0 else ActionResult.FAILED
    except FileNotFoundError:
        return ActionResult.UNAVAILABLE


def clipboard_get() -> tuple[ActionResult, str]:
    """获取剪贴板内容"""
    ok, out = _run_termux("termux-clipboard-get")
    return (ActionResult.OK, out) if ok else (ActionResult.UNAVAILABLE, "")


# ── 系统控制 ──────────────────────────────────────────

def set_brightness(level: int) -> ActionResult:
    """设置屏幕亮度 (0-255)"""
    level = max(0, min(255, level))
    ok, _ = _run_termux("termux-brightness", str(level))
    return ActionResult.OK if ok else ActionResult.UNAVAILABLE


def set_volume(stream: str, level: int) -> ActionResult:
    """设置音量 (music/ring/alarm/notification)，level 0-15"""
    level = max(0, min(15, level))
    ok, _ = _run_termux("termux-volume", stream, str(level))
    return ActionResult.OK if ok else ActionResult.UNAVAILABLE


def get_battery() -> Optional[dict]:
    """获取电池状态"""
    ok, out = _run_termux("termux-battery-status")
    if not ok:
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


def vibrate(duration_ms: int = 200) -> ActionResult:
    """触发手机振动"""
    ok, _ = _run_termux("termux-vibrate", "-d", str(duration_ms))
    return ActionResult.OK if ok else ActionResult.UNAVAILABLE


# ── 触控与输入 ────────────────────────────────────────

def tap_screen(x: int, y: int) -> ActionResult:
    """模拟屏幕点击 (需要 ADB 或 root 权限)"""
    ok, _ = _run_shell(f"input tap {x} {y} 2>/dev/null", timeout=3)
    return ActionResult.OK if ok else ActionResult.FORBIDDEN


def swipe_screen(x1: int, y1: int, x2: int, y2: int,
                 duration_ms: int = 300) -> ActionResult:
    """模拟滑动"""
    ok, _ = _run_shell(
        f"input swipe {x1} {y1} {x2} {y2} {duration_ms} 2>/dev/null", timeout=5
    )
    return ActionResult.OK if ok else ActionResult.FORBIDDEN


def press_key(keycode: int) -> ActionResult:
    """模拟按键"""
    ok, _ = _run_shell(f"input keyevent {keycode} 2>/dev/null", timeout=3)
    return ActionResult.OK if ok else ActionResult.FORBIDDEN


def type_text(text: str) -> ActionResult:
    """模拟文本输入"""
    # 先设置剪贴板，再粘贴
    clipboard_set(text)
    time.sleep(0.2)
    ok, _ = _run_shell("input keyevent 279 2>/dev/null", timeout=3)  # KEYCODE_PASTE
    return ActionResult.OK if ok else ActionResult.FORBIDDEN


# ── 屏幕截图 ──────────────────────────────────────────

def screenshot(output_path: str = None) -> tuple[ActionResult, str]:
    """屏幕截图，返回 (结果, 文件路径)"""
    if output_path is None:
        output_path = f"/data/data/com.termux/files/home/tmp/screenshot_{int(time.time())}.png"
    ok, _ = _run_termux("termux-screenshot", output_path)
    if ok:
        return ActionResult.OK, output_path
    # 回退 screencap
    ok2, _ = _run_shell(f"screencap -p {output_path} 2>/dev/null", timeout=5)
    return (ActionResult.OK, output_path) if ok2 else (ActionResult.FORBIDDEN, "")


# ── 设备信息 ──────────────────────────────────────────

def get_device_info() -> dict:
    """获取设备基本信息"""
    info = {}
    ok, out = _run_termux("termux-telephony-deviceinfo")
    if ok:
        try:
            info = json.loads(out)
        except json.JSONDecodeError:
            pass
    # 补充
    _, model = _run_shell("getprop ro.product.model 2>/dev/null")
    if model:
        info["model"] = model
    _, brand = _run_shell("getprop ro.product.brand 2>/dev/null")
    if brand:
        info["brand"] = brand
    return info
