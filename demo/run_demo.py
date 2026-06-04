"""灵绘完整演示 — 四层管线串联（无需 API 密钥）

🧠 大脑: 模拟 LLM（规则引擎）
💫 灵魂: 角色人格 + 记忆 + 情绪
🦾 手脚: DroidWright 手机自动化
🏗️ 地基: (预留)

用法: python3 demo/run_demo.py
"""

import os
import re
import sys
import time

# 确保项目根目录在 path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from personality.character import LingHuiCharacter
from personality.emotions import EmotionState, Mood
from personality.memory import ConversationMemory
from automation.droidwright import DroidWright, ExecutionStatus
from demo.mock_engine import mock_chat


# ── 颜色输出 ──────────────────────────────────────────

C = {
    "cyan": "\033[36m",
    "green": "\033[32m",
    "yellow": "\033[33m",
    "magenta": "\033[35m",
    "red": "\033[31m",
    "bold": "\033[1m",
    "dim": "\033[2m",
    "reset": "\033[0m",
}


def cprint(color: str, text: str):
    print(f"{C.get(color, '')}{text}{C['reset']}")


# ── 主演示 ────────────────────────────────────────────


def main():
    character = LingHuiCharacter()
    memory = ConversationMemory(max_turns=20)
    emotion = EmotionState()
    droidwright = DroidWright()

    # ── 启动画面 ──
    print()
    cprint("bold", "=" * 56)
    cprint("magenta", "  🎨 灵绘 (Ling Hui) — AI 虚拟桌面精灵")
    cprint("cyan", "  四层管线完整演示")
    cprint("dim", "=" * 56)
    print()
    cprint("green", "  🧠 大脑: 模拟 LLM 规则引擎")
    cprint("green", "  💫 灵魂: 角色人格 + 记忆 + 情绪")
    cprint("green", "  🦾 手脚: DroidWright 手机自动化")
    cprint("green", "  🏗️ 地基: Android 悬浮窗 (预留)")
    print()
    cprint("dim", f"  角色: {character.name} | 性格: {', '.join(character.traits)}")
    cprint("dim", f"  输入 '退出'/'quit' 结束 | 输入 '状态' 查看系统状态")
    print()
    cprint("bold", "-" * 56)

    turn = 0
    while True:
        try:
            user = input(f"\n{C['cyan']}你:{C['reset']} ").strip()
        except (EOFError, KeyboardInterrupt):
            break

        if not user:
            continue
        if user.lower() in ("退出", "quit", "exit", "q"):
            cprint("magenta", f"\n{character.name}: 再见啦！灵绘随时等你回来 💤")
            break
        if user == "状态":
            _show_status(emotion, memory, droidwright)
            continue

        turn += 1
        cprint("dim", f"[第 {turn} 轮]")

        # ── 步骤 1: 情绪更新 ──
        emotion.on_user_message(user)
        mood_before = emotion.mood.value
        time.sleep(0.02)

        # ── 步骤 2: 记忆存储 ──
        memory.add_user(user)

        # ── 步骤 3: LLM 对话（模拟引擎）──
        cprint("dim", f"[情绪: {mood_before}] [记忆: {len(memory)} 轮]")
        reply = mock_chat(user)
        time.sleep(0.05)

        # ── 步骤 4: 自动化执行 ──
        action_outcome = droidwright.execute(reply)
        action_hint = ""
        if action_outcome["status"] != ExecutionStatus.NO_ACTION:
            action_hint = droidwright.get_response_hint(action_outcome)
            if action_outcome["status"] == ExecutionStatus.EXECUTED:
                cprint("green", f"  ⚡ {action_hint}")
            elif action_outcome["status"] == ExecutionStatus.FORBIDDEN:
                cprint("yellow", f"  ⚡ {action_hint}")
            else:
                cprint("red", f"  ⚡ {action_hint}")

        # ── 步骤 5: 情绪 & 记忆更新 ──
        emotion.on_reply()
        memory.add_assistant(reply)

        # ── 步骤 6: 显示回复 ──
                # 剥离 {ACTION: ...} 标记以便显示
        clean_reply = re.sub(r"\{ACTION:\s*\{.*?\}\s*\}", "", reply).strip()
        cprint("magenta", f"{character.name}: {clean_reply}")

        # 如果有自动化结果且没内嵌在回复中，追加显示
        if action_hint and action_hint not in reply:
            pass  # 已经在上方显示了


def _show_status(emotion: EmotionState, memory: ConversationMemory,
                 droidwright: DroidWright):
    """显示系统内部状态"""
    print()
    cprint("yellow", "  📊 系统状态")
    cprint("dim", f"    情绪: {emotion.mood.value} (精力: {emotion._energy:.2f})")
    cprint("dim", f"    记忆: {len(memory)} 轮")
    cprint("dim", f"    最后操作: {droidwright.last_result}")
    cprint("dim", f"    记忆预览: {memory.summary()}")
    print()


if __name__ == "__main__":
    main()
