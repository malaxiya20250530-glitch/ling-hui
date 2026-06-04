"""语音合成模块 — 文本 → 音频 → 播放"""

import json
import os
import tempfile
import urllib.request
import urllib.error

from .audio_utils import play_audio


class SpeechSynthesizer:
    """文本转语音，支持 OpenAI TTS API"""

    def __init__(self, api_key: str = None,
                 model: str = "tts-1",
                 voice: str = "nova",
                 speed: float = 1.0):
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY", "")
        self.model = model
        self.voice = voice
        self.speed = speed

    def synthesize(self, text: str, output_path: str = None) -> str:
        """将文本合成为音频文件，返回文件路径"""
        if not self.api_key:
            raise RuntimeError("未设置 OPENAI_API_KEY")

        if output_path is None:
            output_path = os.path.join(tempfile.gettempdir(),
                                       f"lh_tts_{os.getpid()}.mp3")

        payload = json.dumps({
            "model": self.model,
            "input": text,
            "voice": self.voice,
            "speed": self.speed,
        }).encode("utf-8")

        req = urllib.request.Request(
            "https://api.openai.com/v1/audio/speech",
            data=payload,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                audio_data = resp.read()
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"TTS API 错误 {e.code}: {body}")
        except urllib.error.URLError as e:
            raise RuntimeError(f"TTS 网络错误: {e.reason}")

        if not audio_data:
            raise RuntimeError("TTS 返回空音频数据")

        with open(output_path, "wb") as f:
            f.write(audio_data)

        return output_path

    def speak(self, text: str):
        """合成并立即播放"""
        audio_path = self.synthesize(text)
        play_audio(audio_path)
        return audio_path


def speak_text(text: str,
               api_key: str = None,
               voice: str = "nova") -> str:
    """便捷函数：直接合成并播放"""
    synth = SpeechSynthesizer(api_key=api_key, voice=voice)
    return synth.speak(text)
