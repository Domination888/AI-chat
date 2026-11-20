"""CPU ASR/TTS with verified local ONNX models; no model download code exists here."""
import os
import hmac
import argparse
import io
import json
import re
import subprocess
import tempfile
import threading
from pathlib import Path
import numpy as np
import soundfile as sf
import sherpa_onnx
import uvicorn
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import Response, JSONResponse
from pydantic import BaseModel

parser = argparse.ArgumentParser()
parser.add_argument('--models', type=Path, required=True)
parser.add_argument('--port', type=int, default=12347)
args = parser.parse_args()
models = json.loads((args.models / 'manifest.json').read_text())['models']
app = FastAPI(docs_url=None, redoc_url=None)
@app.middleware('http')
async def authenticate(request, call_next):
    expected = os.environ.get('STORY_VOICE_KEY', '')
    if request.url.path != '/health' and (not expected or not hmac.compare_digest(request.headers.get('authorization', ''), 'Bearer ' + expected)):
        return JSONResponse({'message': 'Unauthorized'}, status_code=401)
    return await call_next(request)

lock = threading.Lock()
asr = None
tts = None

def path(name):
    result = (args.models / name).resolve()
    if not result.is_relative_to(args.models.resolve()):
        raise RuntimeError('Model path is outside the model pack')
    return str(result)

@app.get('/health')
def health():
    return {'status': 'ready', 'mode': 'offline'}

@app.post('/v1/audio/transcriptions')
def transcribe(file: UploadFile = File(...)):
    global asr
    data = file.file.read(20 * 1024 * 1024 + 1)
    if len(data) > 20 * 1024 * 1024:
        raise HTTPException(413, 'Recording is too large')
    with lock, tempfile.TemporaryDirectory() as directory:
        source, wav = Path(directory) / 'audio', Path(directory) / 'audio.wav'
        source.write_bytes(data)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-protocol_whitelist', 'file,pipe', '-format_whitelist', 'wav,matroska,webm,mov,ogg,flac,mp3,aac', '-i', str(source), '-t', '121', '-vn', '-ar', '16000', '-ac', '1', str(wav)], check=True, timeout=20, capture_output=True)
        if sf.info(wav).duration > 120:
            raise HTTPException(413, 'Recording must be shorter than two minutes')
        if asr is None:
            spec = models['asr']
            asr = sherpa_onnx.OfflineRecognizer.from_sense_voice(model=path(spec['file']), tokens=path(spec['tokens']), num_threads=2, use_itn=True, language='', provider='cpu')
        audio, rate = sf.read(wav, dtype='float32')
        stream = asr.create_stream()
        stream.accept_waveform(rate, audio)
        asr.decode_stream(stream)
        return {'text': re.sub(r'<\|.*?\|>', '', stream.result.text)}

class Speech(BaseModel):
    input: str
    voice: str = 'default'
    response_format: str = 'wav'

@app.post('/v1/audio/speech')
def speech(request: Speech):
    global tts
    if len(request.input) > 4000:
        raise HTTPException(413, 'Text is too long')
    with lock:
        if tts is None:
            spec = models['tts']
            kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(model=path(spec['file']), voices=path(spec['voices']), tokens=path(spec['tokens']), data_dir=path(spec['dataDir']), lexicon=','.join(path(s) for s in spec['lexicons']), dict_dir=path(spec['dictDir']))
            config = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=2, provider='cpu'), max_num_sentences=1)
            if not config.validate():
                raise RuntimeError('Invalid TTS model pack')
            tts = sherpa_onnx.OfflineTts(config)
        audio = tts.generate(request.input, sid=models['tts'].get('speaker', 50), speed=1)
        output = io.BytesIO()
        sf.write(output, audio.samples, audio.sample_rate, format='WAV')
        return Response(output.getvalue(), media_type='audio/wav')

if __name__ == '__main__':
    uvicorn.run(app, host='127.0.0.1', port=args.port, access_log=False)
