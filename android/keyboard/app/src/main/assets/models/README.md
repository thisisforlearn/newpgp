# Offline rewrite models (build artifacts)

<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn -->

All files in this directory are **generated in CI** by
`tools/convert_grammar_model.py`. Do not commit binary models here. The directory
exists only to document placement and naming.

The engine is English-only, local-only: no network permission, no analytics, no
plaintext logging. `Tone.GRAMMAR_ONLY` uses the pure-Kotlin rule path and needs no
model at all, so inline rewriting works instantly even before a model is loaded.

## Expected files

| File | Purpose |
| --- | --- |
| `grammar_small_q8.tflite` | Encoder, quantized; default `ModelInfo.fileName` |
| `grammar_small_q8_decoder.tflite` | Decoder, full-prefix input (no KV cache), quantized |
| `tokenizer_vocab.tsv` | `token<TAB>id`, one per line, sorted by id |
| `tokenizer_metadata.txt` | `key=value`: `pad_id`, `eos_id`, `unk_id`, `bos_id`, `max_input_tokens` |
| `manifest.json` | Sizes, SHA-256, recipe, license note, smoke-test result |

The decoder is re-run over the full generated prefix (at most 64 tokens). This is
slower than cached decoding but avoids per-device cache-shape issues. Tensor names
must be `input_ids`, `attention_mask`, `hidden_states` (encoder output) and
`logits` (decoder output). All ids are int32; `hidden_states` and `logits` are
float32. If CI exports a combined single-graph model, `T5Seq2SeqRunner` needs a
different adapter.

## Size: honest numbers

Source: `pszemraj/grammar-synthesis-small` is T5-small, **~77M parameters**.

- `dynamic_wi8_afp32` (int8 weights): roughly **75–85 MB** for encoder + decoder.
- `dynamic_wi4_afp32` (int4 weights): roughly **35–45 MB** for encoder + decoder.

The earlier "30–60 MB q8" estimate was optimistic: 77M bytes alone is ~77 MB.
To fit 30–60 MB, use the int4 recipe and rename the outputs (then update
`ModelInfo.fileName` in `Models.kt`):

```bash
python tools/convert_grammar_model.py \
  --recipe dynamic_wi4_afp32 \
  --encoder-name grammar_small_q4.tflite
```

CI should place the artifacts in this directory, then the IME copies them into
`filesDir/models` on first use (or reads them via an asset-backed `ModelSource`).
Pass that absolute directory as `ToneEngine(modelRoot = ...)`.

## Build-time conversion

```bash
pip install litert-torch 'transformers>=4.45' litert-quantizer ai-edge-litert numpy
python tools/convert_grammar_model.py --strict
```

- `litert-torch` exports the encoder and decoder separately from the Hub model
  (FP32 `.tflite` first, then quantized). This is the current supported path.
- `optimum-cli export tflite` was removed in Transformers 5 / Optimum 2.x. The
  `--export-mode optimum-legacy` path requires pinned `transformers<5` + Optimum
  and only works if it produces two separate graphs.
- LiteRT Quantizer recipes: `dynamic_wi8_afp32`, `dynamic_wi4_afp32`,
  `weight_only_wi8_afp32`, etc. Dynamic quantization needs no calibration data.
- `--strict` fails if the artifact is outside the advisory size window or if
  the smoke test fails. The smoke test loads both models and runs one encoder +
  one decoder step with `ai-edge-litert`; it uses placeholder token ids only.
- The tokenizer shipped to the app is an approximation of SentencePiece
  (longest match + `<0xHH>` byte fallback). It is deterministic but not
  byte-identical; unusual Unicode may reduce quality.

## Dependency required in `app/build.gradle.kts` (build owner adds this)

```
implementation("org.tensorflow:tensorflow-lite:2.17.0")
```

The Kotlin integration is `org.tensorflow.lite.Interpreter` behind
`LiteRtInterpreter` so it can be faked in JVM tests or moved later to the newer
`com.google.ai.edge.litert:litert:2.3.0` `CompiledModel` API. LiteRT Task Library
text-generation helpers target decoder-only prompt completion and are not a good
fit for seq2seq grammar editing, so this package uses the Interpreter directly.

## Licensing caveats — read before shipping

- Model weights: **Apache-2.0** (per the Hugging Face model card).
- Training data: the JFLEG-derived grammar-synthesis dataset is tagged
  **CC BY-NC-SA 4.0** on the model card — non-commercial and share-alike terms
  may apply to the derived weights. **Do not claim commercial clearance** without
  legal review. Keep attribution to the model author, the base
  `google/t5-small-lm-adapt` model and the JFLEG dataset. Do not redistribute the
  dataset itself from the app.
- GPL-3.0-or-later applies to this project's code. The generated model artifact is
  a separate work with the upstream terms above; document them in the app's
  notices.

## Runtime limitations

- No fine-tuning yet. Style prefixes (`professional: `, `friendly: `, `concise: `)
  are heuristic hints for a grammar-correcting model; quality is best-effort.
- Model output is guarded against empty or runaway generations; on failure the
  result falls back to the instant rule-based rewrite.
- Load/unload is lazy and off the UI thread (`loadAsync`, `loadSuspend`,
  `rewriteAsync`, `rewriteSuspend`). Model tones block only when the synchronous
  API is deliberately called on a background thread.
- `unload()` releases the interpreters; `close()` also shuts down the worker thread.
