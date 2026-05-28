#!/usr/bin/env python3
"""
MLX-Audio TTS 入口：安装参考音缓存后启动 uvicorn。

用法（与 mlx_audio.server 相同）:
  python server_entry.py --host 127.0.0.1 --port 9881
"""

from __future__ import annotations

import argparse
import os
from typing import Optional

import uvicorn
from pydantic import BaseModel

# 必须在 import mlx_audio.server 之前打补丁
from ref_voice_cache import cache_stats, install_ref_voice_cache

install_ref_voice_cache()

from mlx_audio.server import app  # noqa: E402


class WarmVoiceRequest(BaseModel):
    ref_audio: str
    ref_text: str = ""
    model: Optional[str] = None


@app.post("/v1/audio/warm-voice")
async def warm_voice_endpoint(payload: WarmVoiceRequest):
    """在推理线程内预热 ref_codes / speaker_embed 缓存（合成极短句触发 ICL）。"""
    import asyncio

    from fastapi import HTTPException
    from mlx_audio.server import SpeechRequest, SpeechTaskPayload, get_inference_broker

    ref_path = os.path.abspath(payload.ref_audio)
    if not os.path.isfile(ref_path):
        raise HTTPException(status_code=404, detail=f"ref_audio not found: {ref_path}")

    model_name = payload.model or os.getenv(
        "MLX_AUDIO_MODEL", "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16"
    )
    speech_req = SpeechRequest(
        model=model_name,
        input="你好",
        ref_audio=ref_path,
        ref_text=payload.ref_text or "",
        lang_code="zh",
        stream=False,
        response_format="pcm",
        max_tokens=64,
    )
    handle = get_inference_broker().submit(
        endpoint_kind="tts",
        model_name=model_name,
        payload=SpeechTaskPayload(request=speech_req),
        normalized_kwargs=speech_req.model_dump(exclude={"model"}, exclude_none=True),
        stream=False,
    )

    while True:
        chunk = await asyncio.to_thread(handle.result_queue.get)
        if chunk.kind == "done":
            break
        if chunk.kind == "error":
            raise HTTPException(status_code=500, detail=str(chunk.error)) from chunk.error

    return {"status": "ok", "path": ref_path, **cache_stats()}


def main():
    parser = argparse.ArgumentParser(description="MLX-Audio TTS with ref voice cache")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9881)
    args = parser.parse_args()

    # 预热：后端启动时 POST /v1/audio/warm-voice（在推理线程内写入缓存）
    print(f"[ref_voice_cache] ready {cache_stats()}")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
