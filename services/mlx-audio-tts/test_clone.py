#!/usr/bin/env python3
"""语音克隆测试：使用黍参考音频 + ref_text"""
from mlx_audio.tts.generate import generate_audio

import os
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
ref_audio_path = os.path.join(PROJECT_ROOT, "services/gpt-sovits/v4/黍/reference_audios/中文/emotions/【默认】让我看看我带来的这些渔具放到哪里好呢？.wav")

generate_audio(
    text="你好，我是黍，今天天气真好啊",
    model="mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16",
    voice="Chelsie",
    lang_code="zh",
    ref_audio=ref_audio_path,
    ref_text="让我看看我带来的这些渔具放到哪里好呢？",
    speed=1.0,
    output_path="./test_output",
    file_prefix="shu_clone",
    audio_format="wav",
    play=False,
    verbose=True,
    save=True,
)
print("语音克隆测试完成！")