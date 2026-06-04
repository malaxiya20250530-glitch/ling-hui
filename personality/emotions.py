"""情绪状态系统 — 基于对话的情绪跟踪"""

from enum import Enum


class Mood(Enum):
    HAPPY = "happy"
    NEUTRAL = "neutral"
    CURIOUS = "curious"
    CONCERNED = "concerned"
    TIRED = "tired"
    PLAYFUL = "playful"


class EmotionState:
    """跟踪灵绘的当前情绪，影响回复语调和表情"""

    def __init__(self):
        self.mood = Mood.NEUTRAL
        self._energy = 1.0  # 0.0 - 1.0
        self._consecutive_questions = 0

    def on_user_message(self, text: str):
        """根据用户输入更新情绪"""
        lower = text.lower()
        if "?" in lower or "？" in lower:
            self._consecutive_questions += 1
            self.mood = Mood.CURIOUS
        elif any(w in lower for w in ["哈哈", "笑", "好玩", "有趣"]):
            self.mood = Mood.PLAYFUL
        elif any(w in lower for w in ["帮忙", "救命", "帮忙", "问题"]):
            self.mood = Mood.CONCERNED
            self._energy = min(1.0, self._energy + 0.2)
        elif any(w in lower for w in ["无聊", "累", "休息"]):
            self._energy = max(0.1, self._energy - 0.1)
            if self._energy < 0.3:
                self.mood = Mood.TIRED
        else:
            if self._consecutive_questions > 5:
                self._energy = max(0.2, self._energy - 0.05)
                self.mood = Mood.TIRED
            else:
                self.mood = Mood.NEUTRAL
            self._consecutive_questions = 0

    def on_reply(self):
        """回复后调用，轻微降低精力"""
        self._energy = max(0.1, self._energy - 0.02)
        if self._energy < 0.2:
            self.mood = Mood.TIRED

    def mood_hint(self) -> str:
        """返回可注入提示词的情绪提示"""
        hints = {
            Mood.HAPPY: "你现在心情很好，语气轻快。",
            Mood.NEUTRAL: "",
            Mood.CURIOUS: "你对用户的话题感到好奇。",
            Mood.CONCERNED: "你认真关注用户的问题。",
            Mood.TIRED: "你有点累了，回复可以简短一些。",
            Mood.PLAYFUL: "你心情轻松，可以适当开个玩笑。",
        }
        return hints.get(self.mood, "")
