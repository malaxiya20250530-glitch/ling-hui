"""语音识别模块 — 音频 → 文本"""

import json
import os
import urllib.request
import urllib.error


class SpeechRecognizer:
    """语音转文字，支持 OpenAI Whisper API"""

    def __init__(self, api_key: str = None, model: str = "whisper-1",
                 language: str = "zh"):
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY", "")
        self.model = model
        self.language = language

    def transcribe(self, audio_path: str) -> str:
        """将音频文件转写为文本"""
        if not self.api_key:
            raise RuntimeError("未设置 OPENAI_API_KEY")

        if not os.path.exists(audio_path):
            raise FileNotFoundError(f"音频文件不存在: {audio_path}")

        boundary = "----LingHuiASRBoundary"
        with open(audio_path, "rb") as f:
            audio_data = f.read()

        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="model"\r\n\r\n'
            f"{self.model}\r\n"
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="language"\r\n\r\n'
            f"{self.language}\r\n"
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file";'
            f' filename="audio.wav"\r\n'
            f"Content-Type: audio/wav\r\n\r\n"
        ).encode("utf-8") + audio_data + f"\r\n--{boundary}--\r\n".encode("utf-8")

        req = urllib.request.Request(
            "https://api.openai.com/v1/audio/transcriptions",
            data=body,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                result = json.loads(resp.read().decode("utf-8"))
                return result.get("text", "").strip()
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"ASR API 错误 {e.code}: {body}")
        except urllib.error.URLError as e:
            raise RuntimeError(f"ASR 网络错误: {e.reason}")


def transcribe_file(audio_path: str,
                    api_key: str = None,
                    model: str = "whisper-1") -> str:
    """便捷函数：直接转写音频文件"""
    recognizer = SpeechRecognizer(api_key=api_key, model=model)
    return recognizer.transcribe(audio_path)
