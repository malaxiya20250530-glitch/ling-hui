"""灵绘 Telegram Bot — 极简稳定版"""

import json, os, re, sys, time, urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from demo.mock_engine import mock_chat
from automation.droidwright import DroidWright

TOKEN = None
for p in ["tgcodex_config.json",
          os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "tgcodex_config.json")]:
    if os.path.exists(p):
        TOKEN = json.load(open(p)).get("telegram_token", "")
        break
TOKEN = TOKEN or os.environ.get("TELEGRAM_BOT_TOKEN", "")

if not TOKEN:
    print("❌ 无 Token"); sys.exit(1)

def api(method, data=None):
    url = f"https://api.telegram.org/bot{TOKEN}/{method}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body,
        headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req, timeout=35) as r:
        return json.loads(r.read())

def reply(chat_id, text):
    api("sendMessage", {"chat_id": chat_id, "text": text[:4000]})

def process(text):
    if text == "/start":
        return "✨ 灵绘上线！我是你的 AI 桌面精灵～\n聊天、讲笑话、设提醒、搜东西……尽管来！"
    if text == "/help":
        return "说「讲个笑话」「搜索天气」「提醒我喝水」试试～"
    if text == "/status":
        return "✅ 灵绘在线"
    r = mock_chat(text)
    dw = DroidWright()
    a = dw.execute(r)
    if a["status"].value != "no_action":
        r += "\n\n⚡ " + dw.get_response_hint(a)
    return re.sub(r'\{ACTION:\s*\{.*?\}\s*\}', '', r).strip()

def main():
    print(f"🤖 @MyCodex2025Bot 已启动", flush=True)
    offset = 0
    while True:
        try:
            result = api("getUpdates", {"offset": offset, "timeout": 20})
            for u in result.get("result", []):
                offset = u["update_id"] + 1
                m = u.get("message")
                if not m or "text" not in m:
                    continue
                cid = m["chat"]["id"]
                txt = m["text"]
                name = m["from"].get("first_name", "?")
                print(f"[{name}] {txt}", flush=True)
                api("sendChatAction", {"chat_id": cid, "action": "typing"})
                ans = process(txt)
                if ans:
                    reply(cid, ans)
                    print(f"[灵绘] {ans[:50]}", flush=True)
            time.sleep(1)
        except KeyboardInterrupt:
            print("\n👋 关闭"); break
        except Exception as e:
            print(f"⚠️ {e}", flush=True)
            time.sleep(3)

if __name__ == "__main__":
    main()
