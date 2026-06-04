"""对话记忆系统 — 滑动窗口上下文管理"""

from collections import deque


class ConversationMemory:
    """维护最近 N 轮对话历史的滑动窗口"""

    def __init__(self, max_turns: int = 20):
        self._turns = deque(maxlen=max_turns)

    def add_user(self, text: str):
        """记录用户发言"""
        self._turns.append({"role": "user", "content": text})

    def add_assistant(self, text: str):
        """记录助手回复"""
        self._turns.append({"role": "assistant", "content": text})

    def get_context(self) -> list[dict]:
        """返回适合注入 LLM 的消息上下文列表"""
        return list(self._turns)

    def clear(self):
        """清空记忆"""
        self._turns.clear()

    def last_user_msg(self) -> str:
        """获取最近一条用户消息，用于快速引用"""
        for turn in reversed(self._turns):
            if turn["role"] == "user":
                return turn["content"]
        return ""

    def __len__(self) -> int:
        return len(self._turns)

    def summary(self) -> str:
        """生成对话摘要，供提示词注入"""
        if len(self._turns) < 2:
            return "这是对话刚开始。"
        recent = list(self._turns)[-6:]
        lines = []
        for t in recent:
            role = "用户" if t["role"] == "user" else "灵绘"
            lines.append(f"{role}: {t['content'][:80]}")
        return "最近对话：\n" + "\n".join(lines)
