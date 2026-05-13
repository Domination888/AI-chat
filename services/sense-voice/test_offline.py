"""
SenseVoiceSmall 离线冒烟测试
- 加载 iic/SenseVoiceSmall（首次会从 modelscope 下载，约 ~234MB）
- 优先 mps，失败回退 cpu
- 用法：python test_offline.py [path/to/audio.wav]
       不传参时使用同目录 sample.wav（缺则用 ffmpeg 生成 1kHz 正弦波兜底）
"""
import os, sys, time, subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent


def ensure_sample_wav() -> str:
    sample = HERE / "sample.wav"
    if sample.exists():
        return str(sample)
    print("[info] 未找到 sample.wav，用 ffmpeg 生成 1kHz 正弦波兜底（仅验管线）...")
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=1000:duration=3",
         "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(sample)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return str(sample)


def pick_device() -> str:
    try:
        import torch
        if torch.backends.mps.is_available():
            return "mps"
    except Exception:
        pass
    return "cpu"


def main() -> int:
    audio = sys.argv[1] if len(sys.argv) > 1 else ensure_sample_wav()
    if not os.path.exists(audio):
        print(f"[error] audio not found: {audio}")
        return 1

    device = pick_device()
    print(f"[info] device = {device}")
    print(f"[info] audio  = {audio}")

    from funasr import AutoModel

    t0 = time.time()
    print("[info] loading model iic/SenseVoiceSmall ...")
    try:
        model = AutoModel(
            model="iic/SenseVoiceSmall",
            vad_model="fsmn-vad",
            vad_kwargs={"max_single_segment_time": 30000},
            device=device,
            disable_update=True,
        )
    except Exception as e:
        print(f"[warn] device={device} 加载失败: {e}\n[warn] 回退 cpu")
        model = AutoModel(
            model="iic/SenseVoiceSmall",
            vad_model="fsmn-vad",
            vad_kwargs={"max_single_segment_time": 30000},
            device="cpu",
            disable_update=True,
        )
    print(f"[info] model loaded in {time.time() - t0:.2f}s")

    t1 = time.time()
    res = model.generate(
        input=audio, cache={}, language="auto",
        use_itn=True, batch_size_s=60, merge_vad=True, merge_length_s=15,
    )
    print(f"[info] generate cost {time.time() - t1:.2f}s")
    print(f"[result-raw] {res}")
    try:
        from funasr.utils.postprocess_utils import rich_transcription_postprocess
        text = rich_transcription_postprocess(res[0]["text"])
        print(f"[result-text] {text}")
    except Exception as e:
        print(f"[warn] postprocess failed: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
