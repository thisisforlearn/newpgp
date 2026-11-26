# The keyboard's local AI (plain-language guide)

NewPGP Keyboard v0.1.4 can suggest a cleaner version of what you typed **without
sending your text anywhere**. This page explains what that feature is, what it is
not, and how to control it. For the install steps, see
[Start: Android keyboard](START_ANDROID_KEYBOARD.md).

## What it actually does

Tap the **sparkle/"rewrite tone" button** while typing and the keyboard offers a
polished version of the current text: fixed grammar, cleaner wording, slightly
more natural phrasing. The suggestion replaces the text in the field only if you
accept it — you can always keep what you originally wrote.

Two important clarifications:

- It is **grammar/wording cleanup**, not a chatbot and not a writing genius.
- It is **not encryption**. The shield button encrypts; the sparkle button edits.
  If you want a secret message, write it first, encrypt it with the shield, and
  only then consider polish — polishing encrypted text makes no sense.

## The default model

The bundled model is
[`pszemraj/grammar-synthesis-small`](https://huggingface.co/pszemraj/grammar-synthesis-small),
converted to a **pair of quantized LiteRT files** (an encoder and a decoder,
`.tflite`) with `litert-torch` and the LiteRT Quantizer, then shipped inside the
APK at `assets/models/` together with tokenizer metadata. It is a small T5-style
text-to-text model (about 77M parameters in its original form) fine-tuned for
single-pass grammar correction on an expanded JFLEG dataset.

"Quantized" means the numbers inside the model were compressed so it is smaller
and faster on a phone. That tradeoff can cost a little accuracy.

## What runs offline — and what does not

| Thing | Where it runs | Needs internet? |
| ----- | ------------- | --------------- |
| AI rewrite (sparkle) | On your device via LiteRT | No |
| Word suggestions / autocorrect | On your device | No |
| PGP encryption (shield) | On your device, in Rust via JNI | No |
| Model download and `.tflite` conversion | Build time, on CI / a developer machine | Yes (once, not on your phone) |

The keyboard declares no `INTERNET` permission. There is no analytics, no
keystroke logging, and no cloud call for the AI feature. The public model is
downloaded when the APK is **built**, then baked into the app; your typed text
never travels back to Hugging Face or anywhere else.

One honest caveat: the keyboard cannot control other apps. If the app you are
typing into syncs drafts to a cloud service, that app may upload your text before
or after the keyboard touches it. The AI feature itself never uploads anything.

## Switching models

The v0.1.4 APK ships with one bundled default. How you switch depends on what the
keyboard UI exposes (labels may differ slightly between builds):

1. **If a model picker exists in keyboard settings:** open the keyboard's
   Settings screen, find the AI / rewriting section, and choose a different
   bundled model there. Restart the keyboard if prompted.
2. **Otherwise, rebuild the APK with a different model:** put another compatible
   `.tflite` file in `android/keyboard/app/src/main/assets/models/` and rebuild
   the APK (see [VERIFY_BUILD.md](../android/keyboard/VERIFY_BUILD.md)). To
   convert a different Hugging Face model, edit the model choice in
   `tools/convert_grammar_model.py` (or pass a model name if the script supports
   it — run `python tools/convert_grammar_model.py --help` to check) and rerun
   the conversion.

Only models converted to a LiteRT-compatible `.tflite` pair (encoder plus
decoder, with matching tokenizer files) will work. A raw Hugging Face checkpoint
will not. If you rename the model files, update `ModelInfo.fileName` in the
Kotlin `Models.kt` source as well.

## Honest limitations

- It is a **small** grammar-correction model, not a large language model. It can
  miss errors, change a correct sentence unnecessarily, or produce awkward text.
- It is English-centric and trained on general text; names, technical terms,
  code, and other languages may be changed incorrectly.
- It fixes surface grammar and phrasing. It does not check facts, add missing
  meaning, or understand what you actually meant.
- Quantization and beam-search limits on phones favor speed over quality, so the
  on-device result may differ from the Hugging Face demo.
- The first tap on older devices may take a moment while the model is loaded into
  memory. It works without a network connection, but it is not instant.
- Always read the suggestion before accepting it. Treat it like a spell-checker
  suggestion, not an authority.

## License and attribution

- **Model weights:** Apache-2.0 (see the model card linked above).
- **Training dataset (JFLEG / expanded JFLEG):** CC BY-NC-SA 4.0
  (`cc-by-nc-sa-4.0`). This is a **non-commercial, share-alike** license, so
  check those terms before redistributing a derived model or using it in a
  commercial product.
- **LiteRT / TFLite tooling:** Apache-2.0 (Google AI Edge).

The bundled `.tflite` is a converted derivative of the Apache-2.0 model; the
dataset license still applies to what the model learned. Attribution belongs to
the model author (pszemraj) and the JFLEG dataset authors. Keep the model card
and license notices when redistributing the APK.
