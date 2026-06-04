"""意图解析器 — 将 LLM 回复解析为手机操作指令"""

import json
import re
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ParsedAction:
    """解析后的操作指令"""
    action_type: str           # 操作类型：open_app, open_url, notify, toast,
                               #   clipboard, brightness, volume, vibrate,
                               #   screenshot, tap, swipe, key, type_text, search
    params: dict = field(default_factory=dict)
    raw_text: str = ""         # 原始 LLM 输出

    def to_dict(self) -> dict:
        return {
            "action": self.action_type,
            "params": self.params,
        }


class IntentParser:
    """从 LLM 自然语言回复中提取操作意图

    支持两种触发方式：
    1. 自然语言匹配 —— 关键词/模式匹配
    2. JSON 格式标记 —— LLM 回复中以 {ACTION: ...} 包裹
    """

    # 正则模式映射
    PATTERNS = [
        # JSON 标记（优先级最高）
        (r'\{ACTION:\s*(\{.*?\})\s*\}', "_parse_json_block"),
        # 打开应用 — 仅在句首/句中的明确命令触发
        (r'(?:帮[我我]|请|麻烦)(?:打开|启动|运行|开启)([^\s，,。.！!？?、～~…]{1,8})[。！!？?～~]?$', "_parse_open_app"),
        (r'^(?:打开|启动|运行|开启)([^\s，,。.！!？?、～~…]{1,8})[。！!？?～~]?$', "_parse_open_app"),
        # 搜索 — 明确搜索命令
        (r'(?:帮[我我]|请)?(?:搜索|搜一下|搜搜|查一下)\s*(.+?)[。！!？?～~]?$', "_parse_search"),
        # 通知/提醒
        (r'(?:帮[我我]|请)?(?:发|创建|设).{0,3}通知[：:：]\s*(.+?)[。！!？?～~]?$', "_parse_notify"),
        (r'^提醒我\s*(.+?)[。！!？?～~]?$', "_parse_notify"),
        # 剪贴板
        (r'(?:复制|拷贝)\s*[「『]?(.+?)[」』]?(?:到剪贴板)?[。！!？?～~]?$', "_parse_clipboard"),
        # 亮度
        (r'亮度\s*(?:调到|设置|改为|调整到)?\s*(\d+)[%％]?[。！!？?～~]?$', "_parse_brightness"),
        # 音量
        (r'音量\s*(?:调到|设置|改为|调整到)?\s*(\d+)[%％]?[。！!？?～~]?$', "_parse_volume"),
        # 截图
        (r'截[个张下]?图', "_parse_screenshot"),
        # 振动
        (r'[振震]动|嗡嗡', "_parse_vibrate"),
    ]

    @classmethod
    def parse(cls, llm_reply: str) -> Optional[ParsedAction]:
        """从 LLM 回复中提取操作意图，无意图时返回 None"""
        if not llm_reply:
            return None

        # 先尝试 JSON 标记
        json_match = re.search(r'\{ACTION:\s*(\{.*?\})\s*\}', llm_reply, re.DOTALL)
        if json_match:
            return cls._parse_json_block(json_match)

        # 逐模式匹配
        for pattern, method_name in cls.PATTERNS:
            match = re.search(pattern, llm_reply)
            if match:
                method = getattr(cls, method_name, None)
                if method:
                    result = method(match)
                    if result:
                        return result

        return None

    # ── 各模式解析 ──────────────────────────────────

    @staticmethod
    def _parse_json_block(match: re.Match) -> Optional[ParsedAction]:
        """解析 {ACTION: {...}} JSON 块"""
        try:
            data = json.loads(match.group(1))
            action_type = data.pop("type", "unknown")
            return ParsedAction(
                action_type=action_type,
                params=data,
                raw_text=match.group(0),
            )
        except (json.JSONDecodeError, KeyError):
            return None

    @staticmethod
    def _parse_open_app(match: re.Match) -> ParsedAction:
        app_name = match.group(1).strip()
        return ParsedAction(
            action_type="open_app",
            params={"app_name": app_name},
            raw_text=match.group(0),
        )

    @staticmethod
    def _parse_search(match: re.Match) -> ParsedAction:
        query = match.group(1).strip()
        return ParsedAction(
            action_type="search",
            params={"query": query},
            raw_text=match.group(0),
        )

    @staticmethod
    def _parse_notify(match: re.Match) -> ParsedAction:
        content = match.group(1).strip()
        return ParsedAction(
            action_type="notify",
            params={"title": "灵绘提醒", "content": content},
            raw_text=match.group(0),
        )

    @staticmethod
    def _parse_clipboard(match: re.Match) -> ParsedAction:
        text = match.group(1).strip()
        return ParsedAction(
            action_type="clipboard",
            params={"text": text},
            raw_text=match.group(0),
        )

    @staticmethod
    def _parse_brightness(match: re.Match) -> ParsedAction:
        level = min(255, max(0, int(match.group(1))))
        return ParsedAction(
            action_type="brightness",
            params={"level": level},
            raw_text=match.group(0),
        )

    @staticmethod
    def _parse_volume(match: re.Match) -> ParsedAction:
        level = min(15, max(0, int(match.group(1))))
        return ParsedAction(
            action_type="volume",
            params={"stream": "music", "level": level},
            raw_text=match.group(0),
        )

    @staticmethod
    def _parse_screenshot(_match: re.Match) -> ParsedAction:
        return ParsedAction(
            action_type="screenshot",
            params={},
            raw_text="截图",
        )

    @staticmethod
    def _parse_vibrate(_match: re.Match) -> ParsedAction:
        return ParsedAction(
            action_type="vibrate",
            params={"duration_ms": 300},
            raw_text="振动",
        )
