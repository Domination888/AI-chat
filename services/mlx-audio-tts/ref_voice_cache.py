"""
Qwen3-TTS (qwen3_tts) 参考音缓存。

克隆路径使用 ICL：每句都会对 ref_audio 做 speech_tokenizer.encode + speaker_encoder，
与目标文本无关，可按 (ref_audio 路径, mtime) 缓存。
"""

from __future__ import annotations

import os
import threading
import time
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple

import mlx.core as mx
import numpy as np

_ref_path_ctx: ContextVar[Optional[str]] = ContextVar("ref_audio_path", default=None)

_LOADED_AUDIO_CACHE: Dict[Tuple[str, int], Any] = {}
_REF_ARTIFACTS_CACHE: Dict[Tuple[str, int], "RefAudioArtifacts"] = {}
_CACHE_LOCK = threading.Lock()


def _mx_to_numpy(arr: Optional[mx.array]) -> Optional[np.ndarray]:
    if arr is None:
        return None
    return np.array(mx.asarray(arr))


def _numpy_to_mx(arr: Optional[np.ndarray]) -> Optional[mx.array]:
    if arr is None:
        return None
    return mx.array(arr)


@dataclass
class RefAudioArtifacts:
    """与 ref 文件绑定；存 numpy 以便跨推理线程复用。"""

    ref_codes_np: np.ndarray
    speaker_embed_np: Optional[np.ndarray]

    def ref_codes_mx(self) -> mx.array:
        return _numpy_to_mx(self.ref_codes_np)

    def speaker_embed_mx(self) -> Optional[mx.array]:
        return _numpy_to_mx(self.speaker_embed_np)


def cache_stats() -> dict:
    with _CACHE_LOCK:
        return {
            "loaded_audio_entries": len(_LOADED_AUDIO_CACHE),
            "ref_artifacts_entries": len(_REF_ARTIFACTS_CACHE),
        }


def _file_key(path: str) -> Tuple[str, int]:
    abs_path = os.path.abspath(path)
    return abs_path, os.stat(abs_path).st_mtime_ns


def set_ref_path_scope(path: Optional[str]):
    return _ref_path_ctx.set(path)


def reset_ref_path_scope(token) -> None:
    _ref_path_ctx.reset(token)


def get_ref_path() -> Optional[str]:
    return _ref_path_ctx.get()


def get_cached_loaded_audio(path: str) -> Optional[Any]:
    with _CACHE_LOCK:
        return _LOADED_AUDIO_CACHE.get(_file_key(path))


def put_cached_loaded_audio(path: str, audio: Any) -> None:
    with _CACHE_LOCK:
        _LOADED_AUDIO_CACHE[_file_key(path)] = audio


def get_cached_ref_artifacts(path: str) -> Optional[RefAudioArtifacts]:
    with _CACHE_LOCK:
        return _REF_ARTIFACTS_CACHE.get(_file_key(path))


def put_cached_ref_artifacts(path: str, artifacts: RefAudioArtifacts) -> None:
    with _CACHE_LOCK:
        _REF_ARTIFACTS_CACHE[_file_key(path)] = artifacts


def _encode_ref_artifacts(model, ref_audio: mx.array) -> RefAudioArtifacts:
    """与 qwen3_tts._prepare_icl_generation_inputs 中 ref 编码一致。"""
    audio_for_spk = ref_audio
    batched = ref_audio
    if batched.ndim == 1:
        batched = batched[None, None, :]
    elif batched.ndim == 2:
        batched = batched[None, :]

    ref_codes = model.speech_tokenizer.encode(batched)
    mx.eval(ref_codes)

    speaker_embed = None
    if model.speaker_encoder is not None:
        speaker_embed = model.extract_speaker_embedding(audio_for_spk)

    return RefAudioArtifacts(
        ref_codes_np=_mx_to_numpy(ref_codes),
        speaker_embed_np=_mx_to_numpy(speaker_embed),
    )


def warm_voice(model, ref_audio_path: str, ref_text: str = "") -> dict:
    from mlx_audio.tts.generate import load_audio

    path = os.path.abspath(ref_audio_path)
    if not os.path.isfile(path):
        raise FileNotFoundError(f"ref_audio not found: {path}")

    t0 = time.perf_counter()
    audio = get_cached_loaded_audio(path)
    if audio is None:
        audio = load_audio(path, sample_rate=model.sample_rate)
        put_cached_loaded_audio(path, audio)

    artifacts = get_cached_ref_artifacts(path)
    if artifacts is None:
        artifacts = _encode_ref_artifacts(model, audio)
        put_cached_ref_artifacts(path, artifacts)
        print(
            f"[ref_voice_cache] warmed path={path} encode_ms={(time.perf_counter()-t0)*1000:.0f}",
            flush=True,
        )
    else:
        print(f"[ref_voice_cache] already warm path={path}", flush=True)

    return {"path": path, "ref_text": ref_text, **cache_stats()}


def install_qwen3_tts_ref_cache() -> None:
    from mlx_audio.tts.models.qwen3_tts import qwen3_tts

    model_cls = qwen3_tts.Model
    if getattr(model_cls._prepare_icl_generation_inputs, "_ref_cache_installed", False):
        return

    _original_icl = model_cls._prepare_icl_generation_inputs

    def _prepare_icl_cached(
        self,
        text: str,
        ref_audio: mx.array,
        ref_text: str,
        language: str = "auto",
    ):
        path = get_ref_path()
        artifacts = get_cached_ref_artifacts(path) if path and os.path.isfile(path) else None

        if artifacts is None:
            t0 = time.perf_counter()
            result = _original_icl(self, text, ref_audio, ref_text, language)
            if path and os.path.isfile(path):
                # 首次：从本次 ref_audio 提取可复用张量写入缓存
                put_cached_ref_artifacts(path, _encode_ref_artifacts(self, ref_audio))
                print(
                    f"[ref_voice_cache] MISS icl path={path} prepare_ms={(time.perf_counter()-t0)*1000:.0f}",
                    flush=True,
                )
            return result

        print(f"[ref_voice_cache] HIT icl path={path}", flush=True)
        t0 = time.perf_counter()

        # --- 以下与 _prepare_icl_generation_inputs 相同，但复用 ref_codes / speaker_embed ---
        if self.tokenizer is None:
            raise ValueError("Tokenizer not loaded. Call post_load_hook first.")

        config = self.config.talker_config
        ref_codes = artifacts.ref_codes_mx()
        mx.eval(ref_codes)
        ref_time = ref_codes.shape[2]

        ref_chat = f"<|im_start|>assistant\n{ref_text}<|im_end|>\n"
        ref_ids = mx.array(self.tokenizer.encode(ref_chat))[None, :]
        ref_text_ids = ref_ids[:, 3:-2]

        target_chat = (
            f"<|im_start|>assistant\n{text}<|im_end|>\n<|im_start|>assistant\n"
        )
        target_ids = mx.array(self.tokenizer.encode(target_chat))[None, :]
        text_ids = target_ids[:, 3:-5]

        tts_tokens = mx.array(
            [
                [
                    self.config.tts_bos_token_id,
                    self.config.tts_eos_token_id,
                    self.config.tts_pad_token_id,
                ]
            ]
        )
        tts_embeds = self.talker.text_projection(
            self.talker.get_text_embeddings()(tts_tokens)
        )
        tts_bos_embed = tts_embeds[:, 0:1, :]
        tts_eos_embed = tts_embeds[:, 1:2, :]
        tts_pad_embed = tts_embeds[:, 2:3, :]

        combined_text_ids = mx.concatenate([ref_text_ids, text_ids], axis=1)
        text_embed = self.talker.text_projection(
            self.talker.get_text_embeddings()(combined_text_ids)
        )
        text_embed = mx.concatenate([text_embed, tts_eos_embed], axis=1)
        text_lens = text_embed.shape[1]

        first_cb_codes = ref_codes[:, 0, :]
        ref_codec_embed = self.talker.get_input_embeddings()(first_cb_codes)
        for i in range(config.num_code_groups - 1):
            cb_codes = ref_codes[:, i + 1, :]
            ref_codec_embed = (
                ref_codec_embed
                + self.talker.code_predictor.codec_embedding[i](cb_codes)
            )

        codec_bos_embed = self.talker.get_input_embeddings()(
            mx.array([[config.codec_bos_id]])
        )
        codec_embed_icl = mx.concatenate([codec_bos_embed, ref_codec_embed], axis=1)
        codec_lens = codec_embed_icl.shape[1]

        codec_pad_embed = self.talker.get_input_embeddings()(
            mx.array([[config.codec_pad_id]])
        )
        text_with_codec_pad = text_embed + mx.broadcast_to(
            codec_pad_embed, (1, text_lens, codec_pad_embed.shape[-1])
        )
        codec_with_text_pad = codec_embed_icl + mx.broadcast_to(
            tts_pad_embed, (1, codec_lens, tts_pad_embed.shape[-1])
        )
        icl_input_embed = mx.concatenate(
            [text_with_codec_pad, codec_with_text_pad], axis=1
        )
        trailing_text_hidden = tts_pad_embed

        language_id = None
        if language.lower() != "auto" and config.codec_language_id:
            if language.lower() in config.codec_language_id:
                language_id = config.codec_language_id[language.lower()]

        speaker_embed = artifacts.speaker_embed_mx()
        if speaker_embed is not None:
            mx.eval(speaker_embed)

        if language_id is None:
            codec_prefill = [
                config.codec_nothink_id,
                config.codec_think_bos_id,
                config.codec_think_eos_id,
            ]
        else:
            codec_prefill = [
                config.codec_think_id,
                config.codec_think_bos_id,
                language_id,
                config.codec_think_eos_id,
            ]

        codec_prefix_embed = self.talker.get_input_embeddings()(
            mx.array([codec_prefill])
        )
        codec_prefix_suffix = self.talker.get_input_embeddings()(
            mx.array([[config.codec_pad_id, config.codec_bos_id]])
        )

        if speaker_embed is not None:
            codec_prefix_embed = mx.concatenate(
                [
                    codec_prefix_embed,
                    speaker_embed.reshape(1, 1, -1),
                    codec_prefix_suffix,
                ],
                axis=1,
            )
        else:
            codec_prefix_embed = mx.concatenate(
                [codec_prefix_embed, codec_prefix_suffix], axis=1
            )

        role_embed = self.talker.text_projection(
            self.talker.get_text_embeddings()(target_ids[:, :3])
        )

        pad_count = codec_prefix_embed.shape[1] - 2
        pad_embeds = mx.broadcast_to(
            tts_pad_embed, (1, pad_count, tts_pad_embed.shape[-1])
        )
        combined_prefix = mx.concatenate([pad_embeds, tts_bos_embed], axis=1)
        combined_prefix = combined_prefix + codec_prefix_embed[:, :-1, :]

        input_embeds = mx.concatenate(
            [role_embed, combined_prefix, icl_input_embed], axis=1
        )

        print(
            f"[ref_voice_cache] HIT icl prepare_text_ms={(time.perf_counter()-t0)*1000:.0f}",
            flush=True,
        )
        return input_embeds, trailing_text_hidden, tts_pad_embed, ref_codes

    _prepare_icl_cached._ref_cache_installed = True
    model_cls._prepare_icl_generation_inputs = _prepare_icl_cached


def install_server_ref_audio_cache() -> None:
    import mlx_audio.server as server_mod

    adapter_cls = server_mod.TTSExecutionAdapter
    if getattr(adapter_cls.run_serial, "_ref_cache_installed", False):
        return

    _original_run_serial = adapter_cls.run_serial

    def run_serial_cached(self, request):
        speech_request = request.payload.request
        ref_path = None
        if isinstance(speech_request.ref_audio, str) and speech_request.ref_audio.strip():
            ref_path = os.path.abspath(speech_request.ref_audio)

        token = set_ref_path_scope(ref_path)
        try:
            return _original_run_serial(self, request)
        finally:
            reset_ref_path_scope(token)

    run_serial_cached._ref_cache_installed = True
    adapter_cls.run_serial = run_serial_cached


def install_ref_voice_cache() -> None:
    install_qwen3_tts_ref_cache()
    install_server_ref_audio_cache()
