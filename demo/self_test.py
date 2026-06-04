"""灵绘自测试 — 非交互式验证全部四层管线（不执行实际系统操作）"""

import os, sys, re

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from personality.character import LingHuiCharacter
from personality.emotions import EmotionState, Mood
from personality.memory import ConversationMemory
from automation.intent_parser import IntentParser
from automation.droidwright import ExecutionStatus
from demo.mock_engine import mock_chat

p = f = 0
def check(name, cond, detail=""):
    global p, f
    if cond: p += 1; print(f"  ✓ {name}")
    else: f += 1; print(f"  ✗ {name} — {detail}")

# ── 1. 灵魂层 ──
print("💫 灵魂层: 角色 + 记忆 + 情绪")
c = LingHuiCharacter()
check("角色名", c.name == "灵绘")
check("系统提示词", "灵绘" in c.build_system_prompt())

m = ConversationMemory(max_turns=5)
m.add_user("你好"); m.add_assistant("你好！")
check("记忆 2 轮", len(m) == 2)
check("最后用户消息", m.last_user_msg() == "你好")

e = EmotionState()
e.on_user_message("你好？")
check("好奇", e.mood == Mood.CURIOUS)
e.on_user_message("哈哈")
check("开心", e.mood == Mood.PLAYFUL)

# ── 2. 大脑层 ──
print("\n🧠 大脑层: 模拟对话")
r = mock_chat("你好")
check("问候", len(r) > 5)
r = mock_chat("讲个笑话")
check("笑话", len(r) > 10)
r = mock_chat("打开微信")
check("打开应用含微信", "微信" in r)
r = mock_chat("提醒我喝水")
check("提醒含水", "水" in r)

# ── 3. 手脚层 (意图解析，不执行) ──
print("\n🦾 手脚层: 意图解析 (不执行系统操作)")
tests = [
    ("打开微信", "open_app"),
    ("帮我打开微信", "open_app"),
    ("搜索天气", "search"),
    ("发通知：喝水", "notify"),
    ("提醒我喝水", "notify"),
    ("复制这段", "clipboard"),
    ("亮度200", "brightness"),
    ("音量10", "volume"),
    ("截个图", "screenshot"),
    ("振动", "vibrate"),
]
for text, exp in tests:
    r = IntentParser.parse(text)
    check(f"\"{text}\" → {exp}", r is not None and r.action_type == exp,
          f"got {r.action_type if r else 'None'}")

# 不触发
check("闲聊不触发", IntentParser.parse("今天天气不错") is None)
check("自我介绍不触发", IntentParser.parse("我是灵绘桌面精灵") is None)

# ACTION 标记
r = IntentParser.parse(mock_chat("打开微信"))
check("ACTION JSON 解析", r is not None and r.action_type == "open_app",
      f"got {r}")

# ── 4. 集成 ──
print("\n🔗 集成测试: 全链路 (6 轮对话)")
seq = ["你好", "你是谁", "讲个笑话", "打开微信", "提醒我喝水", "截图"]
for i, msg in enumerate(seq):
    e.on_user_message(msg)
    m.add_user(msg)
    reply = mock_chat(msg)
    parsed = IntentParser.parse(reply)
    clean = re.sub(r'\{ACTION:\s*\{.*?\}\s*\}', '', reply).strip()
    m.add_assistant(reply)
    e.on_reply()
    tag = f" ⚡{parsed.action_type}" if parsed else ""
    print(f"  [{i+1}] \"{msg}\" → \"{clean[:45]}...\"{tag}")

check("全链路不崩溃", True)

# ── 结果 ──
print(f"\n{'='*50}")
print(f"  {p}/{p+f} 通过", end="")
print(" ✅ 全部四层正常！" if f == 0 else f" ⚠️ {f} 失败")
print(f"{'='*50}")
