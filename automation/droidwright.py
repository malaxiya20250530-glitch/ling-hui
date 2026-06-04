"""DroidWright — 手机自动化主执行器

流程：
  LLM 回复 → IntentParser 提取操作 → Actions 执行 → 结果反馈
"""

from enum import Enum
from typing import Optional

from .actions import (
    ActionResult,
    open_app, open_url, search_apps,
    send_notification, show_toast,
    clipboard_set, clipboard_get,
    set_brightness, set_volume,
    get_battery, vibrate,
    tap_screen, swipe_screen, press_key, type_text,
    screenshot, get_device_info,
)
from .intent_parser import IntentParser, ParsedAction


class ExecutionStatus(Enum):
    EXECUTED = "executed"
    PARTIAL = "partial"
    FAILED = "failed"
    NO_ACTION = "no_action"
    FORBIDDEN = "forbidden"


class DroidWright:
    """手机自动化引擎：解析 LLM 输出 → 执行操作 → 返回结果"""

    def __init__(self):
        self.last_result: Optional[dict] = None

    def execute(self, llm_reply: str) -> dict:
        """从 LLM 回复中提取并执行操作

        返回：
        {
            "status": ExecutionStatus,
            "action": str (操作类型),
            "result": ActionResult,
            "message": str (人类可读结果),
            "data": dict (附加上下文数据),
        }
        """
        # 1. 解析意图
        parsed = IntentParser.parse(llm_reply)
        if parsed is None:
            return {
                "status": ExecutionStatus.NO_ACTION,
                "action": None,
                "result": None,
                "message": "未检测到需执行的手机操作",
                "data": {},
            }

        # 2. 执行操作
        action_type = parsed.action_type
        params = parsed.params
        result = ActionResult.FAILED
        data = {}
        message = ""

        try:
            if action_type == "open_app":
                app_name = params.get("app_name", "")
                apps = search_apps(app_name)
                if apps:
                    result = open_app(apps[0])
                    data["matched_package"] = apps[0]
                    message = f"已打开 {app_name}"
                else:
                    # 尝试用 URL 方式（web 应用）
                    result = open_url(f"https://www.google.com/search?q={app_name}")
                    message = f"未找到 '{app_name}' 应用，已用浏览器搜索"

            elif action_type == "open_url":
                result = open_url(params.get("url", ""))
                message = f"已打开链接"

            elif action_type == "search":
                query = params.get("query", "")
                result = open_url(
                    f"https://www.google.com/search?q={query}"
                )
                message = f"已搜索: {query}"

            elif action_type == "notify":
                title = params.get("title", "灵绘")
                content = params.get("content", "")
                result = send_notification(title, content)
                message = f"已发送通知: {content[:30]}"

            elif action_type == "toast":
                result = show_toast(params.get("text", ""))
                message = f"已弹出提示"

            elif action_type == "clipboard":
                result = clipboard_set(params.get("text", ""))
                message = f"已复制到剪贴板"

            elif action_type == "brightness":
                result = set_brightness(params.get("level", 128))
                message = f"亮度已调整为 {params.get('level')}"

            elif action_type == "volume":
                result = set_volume(
                    params.get("stream", "music"),
                    params.get("level", 7),
                )
                message = f"音量已调整为 {params.get('level')}"

            elif action_type == "screenshot":
                result, path = screenshot()
                data["screenshot_path"] = path
                message = "截图已保存"

            elif action_type == "vibrate":
                result = vibrate(params.get("duration_ms", 200))
                message = "已振动"

            elif action_type == "tap":
                result = tap_screen(
                    params.get("x", 0), params.get("y", 0)
                )
                message = f"已点击 ({params.get('x')}, {params.get('y')})"

            elif action_type == "swipe":
                result = swipe_screen(
                    params.get("x1", 0), params.get("y1", 0),
                    params.get("x2", 0), params.get("y2", 0),
                    params.get("duration_ms", 300),
                )
                message = "已滑动"

            elif action_type == "type_text":
                result = type_text(params.get("text", ""))
                message = "已输入文字"

            else:
                message = f"未知操作类型: {action_type}"
                result = ActionResult.FAILED

        except Exception as e:
            message = f"执行异常: {e}"
            result = ActionResult.FAILED

        # 3. 判断状态
        if result == ActionResult.OK:
            status = ExecutionStatus.EXECUTED
        elif result == ActionResult.FORBIDDEN:
            status = ExecutionStatus.FORBIDDEN
            message += " (需要 ADB/root 权限)"
        elif result == ActionResult.UNAVAILABLE:
            status = ExecutionStatus.PARTIAL
            message += " (termux-api 未安装)"
        else:
            status = ExecutionStatus.FAILED

        outcome = {
            "status": status,
            "action": action_type,
            "result": result.value,
            "message": message,
            "data": data,
        }
        self.last_result = outcome
        return outcome

    def get_response_hint(self, outcome: dict) -> str:
        """根据执行结果生成给用户的回复提示"""
        if outcome["status"] == ExecutionStatus.NO_ACTION:
            return ""
        if outcome["status"] == ExecutionStatus.EXECUTED:
            return f"✅ {outcome['message']}"
        if outcome["status"] == ExecutionStatus.FORBIDDEN:
            return f"⚠️ {outcome['message']}"
        return f"❌ 操作失败: {outcome['message']}"


# ── 便捷函数 ──────────────────────────────────────────

def execute_from_llm(llm_reply: str) -> dict:
    """便捷函数：直接从 LLM 回复执行自动化"""
    return DroidWright().execute(llm_reply)
