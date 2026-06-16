"""
SenseVoice ASR HTTP 服务（OpenAI 风格批处理）
- POST /v1/audio/transcriptions  (multipart: file, language?, hotwords?)
    -> { "text": "...", "language": "zh", "emotion": "...", "event": "..." }
- GET  /healthz -> { "status": "ok", "device": "mps|cpu", "model_loaded": true }
监听: 127.0.0.1:9000
"""
import os
import re
import logging
import tempfile
import subprocess
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("sensevoice-server")

STATE = {"model": None, "device": "cpu", "loaded": False}


def model_cache_dir() -> str:
    for key in ("SENSEVOICE_MODEL_DIR", "FUNASR_MODEL_DIR", "MODELSCOPE_CACHE"):
        val = os.environ.get(key)
        if val:
            os.makedirs(val, exist_ok=True)
            return val
    return ""


def ffmpeg_cmd() -> str:
    bundled = os.environ.get("FFMPEG_PATH")
    if bundled and os.path.isfile(bundled):
        return bundled
    runtime_ffmpeg = os.path.join(os.path.dirname(__file__), "ffmpeg")
    if os.path.isfile(runtime_ffmpeg):
        return runtime_ffmpeg
    try:
        import imageio_ffmpeg
        exe = imageio_ffmpeg.get_ffmpeg_exe()
        if exe and os.path.isfile(exe):
            return exe
    except Exception:
        pass
    return "ffmpeg"


def pick_device() -> str:
    try:
        import torch
        if torch.backends.mps.is_available():
            return "mps"
    except Exception:
        pass
    return "cpu"


def load_model():
    from funasr import AutoModel
    cache = model_cache_dir()
    if cache:
        os.environ.setdefault("MODELSCOPE_CACHE", cache)
        os.environ.setdefault("FUNASR_MODEL_DIR", cache)
    device = pick_device()
    log.info("loading SenseVoiceSmall on device=%s cache=%s ...", device, cache or "(default)")
    model_id = os.environ.get("SENSEVOICE_MODEL_ID", "iic/SenseVoiceSmall")
    kwargs = dict(
        model=model_id,
        vad_model="fsmn-vad",
        vad_kwargs={"max_single_segment_time": 30000},
        device=device,
        disable_update=True,
    )
    if cache:
        kwargs["model_dir"] = cache
    try:
        m = AutoModel(**kwargs)
    except Exception as e:
        log.warning("load on %s failed: %s, fallback to cpu", device, e)
        device = "cpu"
        kwargs["device"] = "cpu"
        m = AutoModel(**kwargs)
    STATE["model"] = m
    STATE["device"] = device
    STATE["loaded"] = True
    log.info("model loaded on %s", device)


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_model()
    yield


app = FastAPI(title="SenseVoice ASR", lifespan=lifespan)


@app.get("/healthz")
async def healthz():
    return {"status": "ok" if STATE["loaded"] else "loading",
            "device": STATE["device"],
            "model_loaded": STATE["loaded"]}


def _ensure_16k_mono_wav(src_path: str) -> str:
    """用 ffmpeg 把任意格式转成 16k mono pcm_s16le wav。"""
    dst = src_path + ".16k.wav"
    cmd = [ffmpeg_cmd(), "-y", "-i", src_path, "-ar", "16000", "-ac", "1",
           "-c:a", "pcm_s16le", dst]
    r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {r.stderr.decode('utf-8', 'ignore')[:500]}")
    return dst


# 正则抽取 funasr 富文本标签 <|zh|><|HAPPY|><|Speech|>...
TAG_RE = re.compile(r"<\|([^|]+)\|>")
LANG_SET = {"zh", "en", "yue", "ja", "ko", "nospeech", "auto"}
EVENT_SET = {"Speech", "BGM", "Applause", "Laughter", "Cry", "Sneeze", "Breath", "Cough"}
EMOTION_SET = {"HAPPY", "SAD", "ANGRY", "NEUTRAL", "FEARFUL", "DISGUSTED", "SURPRISED", "EMO_UNKNOWN"}


def parse_sensevoice_text(raw: str):
    language, emotion, event = None, None, None
    for tag in TAG_RE.findall(raw or ""):
        if tag in LANG_SET and language is None:
            language = tag
        elif tag in EMOTION_SET and emotion is None:
            emotion = tag
        elif tag in EVENT_SET and event is None:
            event = tag
    try:
        from funasr.utils.postprocess_utils import rich_transcription_postprocess
        text = rich_transcription_postprocess(raw or "")
    except Exception:
        text = TAG_RE.sub("", raw or "").strip()
    return text, language, emotion, event


@app.post("/v1/audio/transcriptions")
async def transcribe(
    file: UploadFile = File(...),
    language: str = Form("auto"),
    hotwords: str = Form(""),
):
    if not STATE["loaded"]:
        raise HTTPException(status_code=503, detail="model not loaded yet")

    suffix = os.path.splitext(file.filename or "")[1] or ".bin"
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    try:
        tmp.write(await file.read())
        tmp.flush()
        tmp.close()

        try:
            wav_path = _ensure_16k_mono_wav(tmp.name)
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"audio decode failed: {e}")

        try:
            res = STATE["model"].generate(
                input=wav_path,
                cache={},
                language=language or "auto",
                use_itn=True,
                batch_size_s=60,
                merge_vad=True,
                merge_length_s=15,
                hotword=hotwords or None,
            )
        except Exception as e:
            log.exception("generate failed")
            raise HTTPException(status_code=500, detail=f"asr failed: {e}")

        raw = res[0].get("text", "") if res else ""
        text, lang, emo, evt = parse_sensevoice_text(raw)
        return JSONResponse({
            "text": text,
            "language": lang,
            "emotion": emo,
            "event": evt,
            "raw": raw,
        })
    finally:
        try:
            os.unlink(tmp.name)
        except Exception:
            pass
        try:
            os.unlink(tmp.name + ".16k.wav")
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("SENSEVOICE_PORT", "9000"))
    uvicorn.run("server:app", host="127.0.0.1", port=port, workers=1, reload=False)
