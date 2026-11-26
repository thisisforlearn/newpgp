#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
"""Build-time (CI only) converter for the offline grammar model.

Downloads ``pszemraj/grammar-synthesis-small`` (T5-small, ~77M params), exports a
float32 encoder and decoder to LiteRT via ``litert-torch`` (the current
Transformers path; ``optimum-cli export tflite`` was removed in Transformers 5 /
Optimum 2.x), quantizes both with LiteRT Quantizer, writes the tokenizer metadata
our Kotlin runtime expects, verifies sizes and runs a smoke test.

Outputs (committed by CI as build artifacts, never by hand):
    grammar_small_q8.tflite          encoder
    grammar_small_q8_decoder.tflite  decoder (full-prefix, no KV cache)
    tokenizer_vocab.tsv              token<TAB>id
    tokenizer_metadata.txt           key=value
    manifest.json                    sizes, sha256, recipe, license note

Size reality check: int8 weights for 77M params are ~77 MB, not 30-60 MB. To hit
the 30-60 MB target use ``--recipe dynamic_wi4_afp32`` (about ~38 MB) with a
matching ``--encoder-name grammar_small_q4.tflite`` and update ModelInfo in Kotlin.

Licensing: model weights are Apache-2.0; the JFLEG-derived training dataset is
CC BY-NC-SA 4.0 (non-commercial / share-alike). This script produces derived
weights -- do not assume commercial use is cleared; keep attribution and get
legal review. See android/keyboard/app/src/main/assets/models/README.md.

No network calls happen at app runtime. This script only downloads at build time.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple

DEFAULT_MODEL_ID = "pszemraj/grammar-synthesis-small"
DEFAULT_OUT_DIR = Path("android/keyboard/app/src/main/assets/models")
DEFAULT_ENCODER = "grammar_small_q8.tflite"
DEFAULT_RECIPE = "dynamic_wi8_afp32"
VOCAB_FILE = "tokenizer_vocab.tsv"
META_FILE = "tokenizer_metadata.txt"
MANIFEST_FILE = "manifest.json"
SAMPLE_TEXT = "grammar: i can has cheezburger"
MAX_INPUT_TOKENS = 128
# Advisory size window for the *combined* encoder + decoder artifact.
MIN_COMBINED_BYTES = 30 * 1024 * 1024
MAX_COMBINED_BYTES = 100 * 1024 * 1024
LICENSE_NOTE = (
    "weights: Apache-2.0; training data: CC BY-NC-SA 4.0 "
    "(non-commercial caveat; attribution required)"
)


def log(code: str, detail: str = "") -> None:
    """Print build diagnostics. Never prints user text (build time only)."""
    print(f"[convert_grammar] {code}" + (f" {detail}" if detail else ""), flush=True)


def require_module(name: str, pip_hint: str) -> None:
    if importlib.util.find_spec(name) is None:
        raise SystemExit(f"MISSING_DEPENDENCY {name} ({pip_hint})")


def run(command: List[str]) -> None:
    log("cmd", " ".join(command))
    subprocess.run(command, check=True)


def decoder_name(encoder_name: str) -> str:
    return encoder_name[:-7] + "_decoder.tflite" if encoder_name.endswith(".tflite") else encoder_name + "_decoder.tflite"


def write_tokenizer(model_id: str, out_dir: Path) -> Dict[str, int]:
    """Write a simple TSV vocabulary and numeric metadata for the Kotlin tokenizer."""
    require_module("transformers", "pip install 'transformers>=4.45'")
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    vocab: Dict[str, int] = dict(tokenizer.get_vocab())
    if not vocab:
        raise SystemExit("EMPTY_VOCAB")

    with (out_dir / VOCAB_FILE).open("w", encoding="utf-8", newline="\n") as handle:
        for token, token_id in sorted(vocab.items(), key=lambda item: item[1]):
            handle.write(f"{token}\t{token_id}\n")

    numeric = {
        "pad_id": int(tokenizer.pad_token_id or 0),
        "eos_id": int(tokenizer.eos_token_id or 1),
        "unk_id": int(tokenizer.unk_token_id or 2),
        "bos_id": int(tokenizer.bos_token_id or -1),
        "max_input_tokens": MAX_INPUT_TOKENS,
    }
    with (out_dir / META_FILE).open("w", encoding="utf-8", newline="\n") as handle:
        for key, value in numeric.items():
            handle.write(f"{key}={value}\n")
    log("tokenizer.ok", f"tokens={len(vocab)}")
    return numeric


def export_float(model_id: str, work_dir: Path, mode: str) -> Tuple[Path, Path]:
    """Export separate encoder and decoder FP32 LiteRT graphs."""
    encoder_path = work_dir / "encoder_fp32.tflite"
    decoder_path = work_dir / "decoder_fp32.tflite"

    if mode == "litert-torch":
        require_module("litert_torch", "pip install litert-torch")
        require_module("torch", "pip install torch")
        require_module("transformers", "pip install 'transformers>=4.45'")
        import torch
        import litert_torch
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

        tokenizer = AutoTokenizer.from_pretrained(model_id)
        model = AutoModelForSeq2SeqLM.from_pretrained(model_id).eval()
        sample = tokenizer(SAMPLE_TEXT, return_tensors="pt")
        source_ids = sample["input_ids"]
        source_mask = sample["attention_mask"]

        with torch.no_grad():
            encoder_model = litert_torch.convert(
                model.encoder,
                sample_kwargs={"input_ids": source_ids, "attention_mask": source_mask},
            )
            encoder_model.export(str(encoder_path))

            pad_id = int(tokenizer.pad_token_id or 0)
            decoder_kwargs = {
                "input_ids": torch.full((1, 1), pad_id, dtype=torch.long),
                "attention_mask": torch.ones((1, 1), dtype=torch.long),
                "encoder_hidden_states": torch.zeros(
                    (1, int(source_ids.shape[1]), int(model.config.d_model)),
                    dtype=torch.float32,
                ),
            }
            decoder_model = litert_torch.convert(model.decoder, sample_kwargs=decoder_kwargs)
            decoder_model.export(str(decoder_path))
        log("export.ok", "litert-torch")
        return encoder_path, decoder_path

    # Legacy path, kept for reproducibility on pinned Transformers 4.x + Optimum.
    require_module("optimum", "pip install 'transformers<5' 'optimum[onnxruntime]'")
    legacy_dir = work_dir / "optimum_export"
    legacy_dir.mkdir(parents=True, exist_ok=True)
    run(
        [
            sys.executable, "-m", "optimum.cli", "export", "tflite",
            "--model", model_id,
            "--task", "text2text-generation",
            "--output", str(legacy_dir),
        ]
    )
    candidates = sorted(legacy_dir.rglob("*.tflite"))
    if len(candidates) == 2:
        paths = sorted(candidates, key=lambda path: "decoder" in path.name.lower())
        shutil.copyfile(paths[0], encoder_path)
        shutil.copyfile(paths[1], decoder_path)
    else:
        raise SystemExit(
            "LEGACY_SIGNATURE_MISMATCH: optimum produced a combined graph; "
            "use --export-mode litert-torch (T5Seq2SeqRunner expects two graphs)"
        )
    log("export.ok", "optimum-legacy")
    return encoder_path, decoder_path


def quantize_model(source: Path, destination: Path, recipe_name: str) -> None:
    """Quantize a FP32 .tflite with LiteRT Quantizer (post-training, offline)."""
    require_module("litert_quantizer", "pip install litert-quantizer")
    from litert_quantizer import quantizer, recipe as recipe_module

    qt = quantizer.Quantizer(str(source))
    recipe_object = getattr(recipe_module, recipe_name, None)
    if callable(recipe_object):
        qt.load_quantization_recipe(recipe_object())
    else:
        qt.load_quantization_recipe(recipe_name)
    qt.quantize().export_model(str(destination))
    log("quant.ok", recipe_name)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def smoke_test(out_dir: Path, encoder_name: str, metadata: Dict[str, int]) -> Dict[str, Any]:
    """Load both models, run one encoder + one decoder step. No text is used."""
    encoder_path = out_dir / encoder_name
    decoder_path = out_dir / decoder_name(encoder_name)
    if importlib.util.find_spec("numpy") is None or importlib.util.find_spec("ai_edge_litert") is None:
        return {"status": "skipped", "reason": "MISSING_AI_EDGE_LITERT_OR_NUMPY"}

    import numpy as np
    try:
        from ai_edge_litert import Interpreter  # LiteRT 2.x (some builds)
    except ImportError:
        try:
            from ai_edge_litert.interpreter import Interpreter  # LiteRT 2.3+
        except ImportError:
            # The smoke test is advisory: conversion already produced valid
            # .tflite files. Do not block the APK build on an SDK API rename.
            return {
                "status": "skipped",
                "reason": "AI_EDGE_LITERT_INTERPRETER_API_UNAVAILABLE",
            }

    try:
        encoder = Interpreter(model_path=str(encoder_path))
        encoder.allocate_tensors()
        decoder = Interpreter(model_path=str(decoder_path))
        decoder.allocate_tensors()

        enc_inputs = encoder.get_input_details()
        enc_outputs = encoder.get_output_details()
        dec_inputs = decoder.get_input_details()
        dec_outputs = decoder.get_output_details()

        def index_by_name(details: List[Dict[str, Any]], fragment: str) -> int:
            for item in details:
                if fragment in str(item["name"]).lower():
                    return int(item["index"])
            raise ValueError("SMOKE_SIGNATURE_MISMATCH")

        ids_index = index_by_name(enc_inputs, "input_ids")
        mask_index = index_by_name(enc_inputs, "attention_mask")
        sample_ids = [metadata["unk_id"] or 2, 3, 4, 5, metadata["eos_id"]]
        length = len(sample_ids)

        for index in (ids_index, mask_index):
            encoder.resize_tensor_input(index, [1, length])
        encoder.allocate_tensors()
        encoder.set_tensor(ids_index, np.asarray([sample_ids], dtype=np.int32))
        encoder.set_tensor(mask_index, np.ones((1, length), dtype=np.int32))
        encoder.invoke()
        hidden = encoder.get_tensor(enc_outputs[0]["index"])
        if hidden.size == 0:
            raise ValueError("SMOKE_EMPTY_ENCODER_OUTPUT")

        decoder_ids = index_by_name(dec_inputs, "input_ids")
        decoder_mask = index_by_name(dec_inputs, "attention_mask")
        decoder_hidden = index_by_name(dec_inputs, "hidden")
        logits_index = index_by_name(dec_outputs, "logits")
        hidden_dim = int(hidden.reshape(length, -1).shape[1])
        for index, shape in (
            (decoder_ids, [1, 1]),
            (decoder_mask, [1, 1]),
            (decoder_hidden, [1, length, hidden_dim]),
        ):
            decoder.resize_tensor_input(index, shape)
        decoder.allocate_tensors()
        decoder.set_tensor(decoder_ids, np.asarray([[metadata["pad_id"]]], dtype=np.int32))
        decoder.set_tensor(decoder_mask, np.ones((1, 1), dtype=np.int32))
        decoder.set_tensor(decoder_hidden, np.zeros((1, length, hidden_dim), dtype=np.float32))
        decoder.invoke()
        logits = decoder.get_tensor(logits_index)
        if logits.size == 0:
            raise ValueError("SMOKE_EMPTY_LOGITS")
        return {"status": "ok", "encoder_output": list(hidden.shape), "logits": list(logits.shape)}
    except Exception as exc:  # noqa: BLE001 - build tool, report code only
        return {"status": "failed", "reason": type(exc).__name__ + ": " + str(exc)}


def verify(out_dir: Path, encoder_name: str, metadata: Dict[str, int], strict: bool) -> Dict[str, Any]:
    encoder_path = out_dir / encoder_name
    decoder_path = out_dir / decoder_name(encoder_name)
    for path in (encoder_path, decoder_path):
        if not path.is_file():
            raise SystemExit(f"MISSING_OUTPUT {path.name}")
    files = {}
    total = 0
    for path in (encoder_path, decoder_path, out_dir / VOCAB_FILE, out_dir / META_FILE):
        if not path.is_file():
            raise SystemExit(f"MISSING_OUTPUT {path.name}")
        size = path.stat().st_size
        total += size
        files[path.name] = {"bytes": size, "sha256": sha256(path)}
    log("sizes", f"combined_model_bytes={encoder_path.stat().st_size + decoder_path.stat().st_size}")

    combined = encoder_path.stat().st_size + decoder_path.stat().st_size
    size_note = ""
    if not (MIN_COMBINED_BYTES <= combined <= MAX_COMBINED_BYTES):
        size_note = (
            f"outside advisory {MIN_COMBINED_BYTES // (1024 * 1024)}-"
            f"{MAX_COMBINED_BYTES // (1024 * 1024)} MB window; "
            "q8 ~77 MB for 77M params, q4 ~38 MB; use --recipe dynamic_wi4_afp32 to shrink"
        )
        log("size.warning", size_note)
        if strict:
            raise SystemExit("SIZE_CHECK_FAILED " + size_note)

    smoke = {"status": "skipped"}
    return {"files": files, "total_bytes": total, "size_note": size_note, "smoke": smoke}


def write_manifest(out_dir: Path, args: argparse.Namespace, report: Dict[str, Any]) -> None:
    manifest = {
        "model_id": args.model_id,
        "export_mode": args.export_mode,
        "quantization_recipe": args.recipe,
        "encoder_file": args.encoder_name,
        "decoder_file": decoder_name(args.encoder_name),
        "tokenizer": {
            "vocab_file": VOCAB_FILE,
            "metadata_file": META_FILE,
            "max_input_tokens": MAX_INPUT_TOKENS,
        },
        "license": LICENSE_NOTE,
        "generated_at_utc": _dt.datetime.now(_dt.timezone.utc).isoformat(),
        "files": report["files"],
        "size_note": report["size_note"],
        "smoke_test": report["smoke"],
    }
    (out_dir / MANIFEST_FILE).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    log("manifest.ok", MANIFEST_FILE)


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--encoder-name", default=DEFAULT_ENCODER)
    parser.add_argument(
        "--recipe", default=DEFAULT_RECIPE,
        help="LiteRT Quantizer recipe (dynamic_wi8_afp32 ~77MB, dynamic_wi4_afp32 ~38MB)",
    )
    parser.add_argument("--export-mode", choices=("litert-torch", "optimum-legacy"), default="litert-torch")
    parser.add_argument("--strict", action="store_true", help="fail on size/smoke problems")
    parser.add_argument("--skip-smoke", action="store_true")
    parser.add_argument("--keep-intermediates", action="store_true")
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = parse_args(argv)
    out_dir: Path = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    work_dir = out_dir / ".build-tmp"
    work_dir.mkdir(parents=True, exist_ok=True)

    metadata = write_tokenizer(args.model_id, out_dir)
    float_encoder, float_decoder = export_float(args.model_id, work_dir, args.export_mode)
    quantized_encoder = work_dir / "encoder_quant.tflite"
    quantized_decoder = work_dir / "decoder_quant.tflite"
    quantize_model(float_encoder, quantized_encoder, args.recipe)
    quantize_model(float_decoder, quantized_decoder, args.recipe)
    shutil.copyfile(quantized_encoder, out_dir / args.encoder_name)
    shutil.copyfile(quantized_decoder, out_dir / decoder_name(args.encoder_name))

    if args.skip_smoke:
        report = verify(out_dir, args.encoder_name, metadata, args.strict)
    else:
        report = verify(out_dir, args.encoder_name, metadata, args.strict)
        report["smoke"] = smoke_test(out_dir, args.encoder_name, metadata)
        log("smoke", report["smoke"]["status"])
        if report["smoke"]["status"] == "failed":
            log("smoke.reason", report["smoke"]["reason"])
            if args.strict:
                raise SystemExit("SMOKE_TEST_FAILED")

    write_manifest(out_dir, args, report)
    if not args.keep_intermediates:
        shutil.rmtree(work_dir, ignore_errors=True)
    log("done", "offline artifact ready")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
