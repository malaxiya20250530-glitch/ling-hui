"""LLM 对话引擎 — 文本 → 文本（带角色上下文）"""

import json
import os
import urllib.request
import urllib.error


class ChatEngine:
    """大语言模型对话引擎，注入角色人格和对话记忆"""

    def __init__(self, api_key: str = None,
                 base_url: str = "https://api.openai.com",
                 model: str = "gpt-4o-mini",
                 system_prompt: str = "",
                 max_tokens: int = 512,
                 temperature: float = 0.8):
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY", "")
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.system_prompt = system_prompt
        self.max_tokens = max_tokens
        self.temperature = temperature

    def set_system_prompt(self, prompt: str):
        """更新系统提示词"""
        self.system_prompt = prompt

    def chat(self, user_message: str,
             history: list[dict] = None,
             mood_hint: str = "") -> str:
        """发送对话请求，返回模型回复"""
        if not self.api_key:
            raise RuntimeError("未设置 OPENAI_API_KEY")

        messages = [{"role": "system", "content": self.system_prompt}]

        # 注入情绪提示
        if mood_hint:
            messages.append({"role": "system", "content": f"[情绪状态] {mood_hint}"})

        # 注入历史上下文
        if history:
            messages.extend(history)

        messages.append({"role": "user", "content": user_message})

        payload = json.dumps({
            "model": self.model,
            "messages": messages,
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
        }).encode("utf-8")

        url = f"{self.base_url}/v1/chat/completions"
        req = urllib.request.Request(
            url, data=payload,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                result = json.loads(resp.read().decode("utf-8"))
                return result["choices"][0]["message"]["content"].strip()
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"LLM API 错误 {e.code}: {body}")
        except urllib.error.URLError as e:
            raise RuntimeError(f"LLM 网络错误: {e.reason}")
        except (KeyError, IndexError, json.JSONDecodeError) as e:
            raise RuntimeError(f"LLM 响应解析失败: {e}")


def quick_chat(message: str,
               api_key: str = None,
               system_prompt: str = "你是灵绘，一个友好的桌面助手。") -> str:
    """便捷函数：单轮对话"""
    engine = ChatEngine(api_key=api_key, system_prompt=system_prompt)
    return engine.chat(message)
