"""灵绘 AI 管线主控制器 — 串联 ASR → LLM → TTS"""

import json
import os
import sys
import time

from .asr import SpeechRecognizer
from .audio_utils import record_audio, record_until_silence
from .llm import ChatEngine
from .tts import SpeechSynthesizer

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from personality.character import LingHuiCharacter
from personality.emotions import EmotionState
from personality.memory import ConversationMemory
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(__file__)), ".."))
from automation.droidwright import DroidWright


class LingHuiPipeline:
    """灵绘对话管线：语音输入 → LLM 思考 → 语音输出"""

    def __init__(self, config_path: str = None):
        self._cfg = self._load_config(config_path)
        self._api_key = self._load_api_key()

        # 初始化角色
        self.character = LingHuiCharacter(config_path)

        # 初始化记忆
        max_turns = self._cfg.get("character", {}).get("max_memory_turns", 20)
        self.memory = ConversationMemory(max_turns=max_turns)

        # 初始化情绪
        self.emotion = EmotionState()

        # 初始化各引擎
        asr_cfg = self._cfg.get("asr", {})
        self.asr = SpeechRecognizer(
            api_key=self._api_key,
            model=asr_cfg.get("model", "whisper-1"),
            language=asr_cfg.get("language", "zh"),
        )

        llm_cfg = self._cfg.get("llm", {})
        self.llm = ChatEngine(
            api_key=self._api_key,
            base_url=llm_cfg.get("base_url", "https://api.openai.com"),
            model=llm_cfg.get("model", "gpt-4o-mini"),
            max_tokens=llm_cfg.get("max_tokens", 512),
            temperature=llm_cfg.get("temperature", 0.8),
            system_prompt=self.character.build_system_prompt(),
        )

        tts_cfg = self._cfg.get("tts", {})
        # 初始化 DroidWright
        self.droidwright = DroidWright()

        self.tts = SpeechSynthesizer(
            api_key=self._api_key,
            model=tts_cfg.get("model", "tts-1"),
            voice=tts_cfg.get("voice", "nova"),
            speed=tts_cfg.get("speed", 1.0),
        )

        self._wake_word = self._cfg.get("character", {}).get("awake_word", "灵绘")
        self._mode = self._cfg.get("pipeline", {}).get("mode", "push_to_talk")
        self._silence_timeout = self._cfg.get("pipeline", {}).get(
            "silence_timeout_sec", 2.0)
        self._max_record = self._cfg.get("pipeline", {}).get("max_record_sec", 15)

    # ── 配置加载 ──────────────────────────────────────

    @staticmethod
    def _load_config(path: str) -> dict:
        """加载全局配置文件"""
        if path and os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except (json.JSONDecodeError, OSError):
                pass
        # 尝试默认路径
        default = os.path.join(os.path.dirname(__file__), "..", "config",
                               "config.json")
        if os.path.exists(default):
            with open(default, "r", encoding="utf-8") as f:
                return json.load(f)
        return {}

    @staticmethod
    def _load_api_key() -> str:
        """加载 API 密钥（环境变量优先）"""
        key = os.environ.get("OPENAI_API_KEY", "")
        if key:
            return key
        local_cfg = os.path.join(os.path.dirname(__file__), "..", "config",
                                 "config.local.json")
        if os.path.exists(local_cfg):
            try:
                with open(local_cfg, "r", encoding="utf-8") as f:
                    return json.load(f).get("api_keys", {}).get("openai", "")
            except (json.JSONDecodeError, OSError, KeyError):
                pass
        return ""

    # ── 核心流程 ───────────────────────────────────────

    def run_once(self, audio_path: str = None, text_input: str = None) -> dict:
        """执行一轮完整对话：输入 → LLM → 输出，返回结果摘要"""
        # 1. 获取用户输入
        if text_input:
            user_text = text_input
        elif audio_path:
            user_text = self.asr.transcribe(audio_path)
        else:
            raise ValueError("必须提供 audio_path 或 text_input")

        if not user_text:
            return {"user": "", "reply": "", "step": "empty_input"}

        print(f"[灵绘] 🎤 听到: {user_text}")

        # 2. 更新情绪 & 记忆
        self.emotion.on_user_message(user_text)
        self.memory.add_user(user_text)

        # 3. LLM 对话
        history = self.memory.get_context()
        mood_hint = self.emotion.mood_hint()
        reply = self.llm.chat(user_text, history=history, mood_hint=mood_hint)

        # 检查是否有自动化操作需要执行
        action_outcome = self.droidwright.execute(reply)
        if action_outcome["status"].value != "no_action":
            action_hint = self.droidwright.get_response_hint(action_outcome)
            if action_hint:
                reply = reply + "\n" + action_hint

        # 4. 更新情绪 & 记忆
        self.emotion.on_reply()
        self.memory.add_assistant(reply)

        print(f"[灵绘] 💬 回复: {reply}")

        # 5. TTS 合成
        audio_out = self.tts.synthesize(reply)

        return {
            "user": user_text,
            "reply": reply,
            "audio_path": audio_out,
            "mood": self.emotion.mood.value,
            "memory_size": len(self.memory),
        }

    def interactive_text(self):
        """文本交互循环（无语音 I/O，方便调试）"""
        print("=" * 50)
        print(f"  🎨 {self.character.name} 文本模式")
        print(f"  输入 '退出' 或 'quit' 结束对话")
        print("=" * 50)

        while True:
            try:
                user = input("\n你: ").strip()
            except (EOFError, KeyboardInterrupt):
                break

            if user.lower() in ("退出", "quit", "exit", "q"):
                print(f"[{self.character.name}] 再见！👋")
                break

            if not user:
                continue

            result = self.run_once(text_input=user)
            if result["reply"]:
                print(f"{self.character.name}: {result['reply']}")

    def interactive_voice(self):
        """语音交互循环 — 按键说话"""
        print("=" * 50)
        print(f"  🎨 {self.character.name} 语音模式")
        print(f"  按 Enter 开始说话，最长 {self._max_record} 秒")
        print("=" * 50)

        while True:
            try:
                input("\n[按 Enter 开始说话...]")
            except (EOFError, KeyboardInterrupt):
                break

            try:
                audio_path = record_audio(duration_sec=self._max_record)
                result = self.run_once(audio_path=audio_path)
                if result["reply"]:
                    self.tts.speak(result["reply"])
            except RuntimeError as e:
                print(f"[错误] {e}")


# ── CLI 入口 ──────────────────────────────────────────

def main():
    """灵绘 AI 管线 CLI"""
    import argparse

    parser = argparse.ArgumentParser(description="灵绘 AI 虚拟桌面精灵")
    parser.add_argument("--config", "-c", default=None,
                       help="配置文件路径 (默认 config/config.json)")
    parser.add_argument("--mode", "-m", choices=["text", "voice"],
                       default="text", help="交互模式")
    parser.add_argument("--say", "-s", type=str, default=None,
                       help="单次文本对话")
    args = parser.parse_args()

    pipeline = LingHuiPipeline(config_path=args.config)

    if args.say:
        result = pipeline.run_once(text_input=args.say)
        print(result["reply"])
    elif args.mode == "voice":
        pipeline.interactive_voice()
    else:
        pipeline.interactive_text()


if __name__ == "__main__":
    main()
