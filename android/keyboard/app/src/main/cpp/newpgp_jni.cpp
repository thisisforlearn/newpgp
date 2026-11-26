// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//
// Plain-JNI shim for the NewPGP keyboard. It owns all Java <-> Rust string
// conversion and exception throwing; the Rust crate owns all cryptography.
// No plaintext, password or key material is ever logged here.

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

// C ABI exported by crates/newpgp-mobile (src/ffi.rs).
extern "C" {
uint8_t newpgp_init(const char *files_dir_abs, char **out, char **err);
uint8_t newpgp_version(char **out, char **err);
uint8_t newpgp_generate_identity(const char *name, const char *email,
                                const char *password, char **out, char **err);
uint8_t newpgp_public_key(char **out, char **err);
uint8_t newpgp_encrypt(const char *plaintext, const char *recipients_csv,
                       uint8_t sign, const char *password, char **out, char **err);
uint8_t newpgp_decrypt(const char *armored, const char *password,
                       char **out, char **err);
uint8_t newpgp_safety_number(const char *email, char **out, char **err);
void newpgp_free_string(char *s);
}

namespace {

void appendUtf8(std::string &out, uint32_t cp) {
  if (cp <= 0x7F) {
    out.push_back(static_cast<char>(cp));
  } else if (cp <= 0x7FF) {
    out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
  } else if (cp <= 0xFFFF) {
    out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
    out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
  } else {
    out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
    out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
    out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
  }
}

// Java strings are UTF-16. GetStringUTFChars would give us *modified* UTF-8,
// which Rust's String::from_utf8 rejects for emoji (surrogate pairs). Copy the
// UTF-16 code units and decode explicitly, including surrogate pairs.
std::string jstringToUtf8(JNIEnv *env, jstring value) {
  if (value == nullptr) return std::string();
  const jsize length = env->GetStringLength(value);
  if (length <= 0) return std::string();

  std::vector<jchar> units(static_cast<size_t>(length));
  env->GetStringRegion(value, 0, length, units.data());
  if (env->ExceptionCheck()) return std::string();

  std::string out;
  out.reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t cp = units[static_cast<size_t>(i)];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
      const uint32_t low = units[static_cast<size_t>(i + 1)];
      if (low >= 0xDC00 && low <= 0xDFFF) {
        cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
        ++i;
      } else {
        cp = 0xFFFD;  // unpaired high surrogate
      }
    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
      cp = 0xFFFD;  // unpaired low surrogate
    }
    appendUtf8(out, cp);
  }
  return out;
}

// Rust gives us real UTF-8. Encode to UTF-16 so supplementary characters
// (emoji, rare names) survive the round trip unchanged.
jstring utf8ToJstring(JNIEnv *env, const std::string &value) {
  std::vector<jchar> units;
  units.reserve(value.size());
  size_t i = 0;
  while (i < value.size()) {
    const unsigned char lead = static_cast<unsigned char>(value[i++]);
    uint32_t cp;
    size_t extra;
    if (lead < 0x80) {
      cp = lead;
      extra = 0;
    } else if ((lead & 0xE0) == 0xC0) {
      cp = lead & 0x1F;
      extra = 1;
    } else if ((lead & 0xF0) == 0xE0) {
      cp = lead & 0x0F;
      extra = 2;
    } else if ((lead & 0xF8) == 0xF0) {
      cp = lead & 0x07;
      extra = 3;
    } else {
      cp = 0xFFFD;
      extra = 0;
    }
    bool broken = false;
    for (size_t k = 0; k < extra && i < value.size(); ++k, ++i) {
      const unsigned char next = static_cast<unsigned char>(value[i]);
      if ((next & 0xC0) != 0x80) {
        broken = true;
        break;
      }
      cp = (cp << 6) | (next & 0x3F);
    }
    if (extra > 0 && i >= value.size() && !broken) broken = true;
    if (broken || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      cp = 0xFFFD;
    }
    if (cp <= 0xFFFF) {
      units.push_back(static_cast<jchar>(cp));
    } else {
      cp -= 0x10000;
      units.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
      units.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
    }
  }
  if (units.empty()) return env->NewStringUTF("");
  return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

// ThrowNew takes modified UTF-8. Our messages are plain language; replacing
// any non-ASCII byte keeps the exception text safe for the JVM.
void throwJava(JNIEnv *env, const char *message) {
  if (env == nullptr || env->ExceptionCheck()) return;
  jclass type = env->FindClass("java/lang/RuntimeException");
  if (type == nullptr) return;
  std::string safe;
  const std::string original(message != nullptr ? message : "Something went wrong.");
  for (unsigned char c : original) safe.push_back(c < 0x80 ? static_cast<char>(c) : '?');
  env->ThrowNew(type, safe.c_str());
  env->DeleteLocalRef(type);
}

template <typename Fn>
jstring invoke(JNIEnv *env, Fn &&call) {
  char *out = nullptr;
  char *err = nullptr;
  try {
    const uint8_t ok = call(&out, &err);
    if (ok == 0) {
      const char *message = (err != nullptr) ? err : "Something went wrong.";
      throwJava(env, message);
      if (out != nullptr) newpgp_free_string(out);
      if (err != nullptr) newpgp_free_string(err);
      return nullptr;
    }
    const std::string value = (out != nullptr) ? std::string(out) : std::string();
    if (out != nullptr) newpgp_free_string(out);
    if (err != nullptr) newpgp_free_string(err);
    return utf8ToJstring(env, value);
  } catch (...) {
    if (out != nullptr) newpgp_free_string(out);
    if (err != nullptr) newpgp_free_string(err);
    throwJava(env, "The keyboard ran out of memory while handling that request.");
    return nullptr;
  }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) { return JNI_VERSION_1_6; }
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_version(JNIEnv *env, jclass) {
  return invoke(env, [](char **out, char **err) { return newpgp_version(out, err); });
}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_init(JNIEnv *env, jclass, jstring files_dir_abs) {
  const std::string path = jstringToUtf8(env, files_dir_abs);
  return invoke(env, [&path](char **out, char **err) {
    return newpgp_init(path.c_str(), out, err);
  });
}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_generateIdentity(JNIEnv *env, jclass,
                                                       jstring name, jstring email,
                                                       jstring password) {
  const std::string n = jstringToUtf8(env, name);
  const std::string e = jstringToUtf8(env, email);
  const std::string p = jstringToUtf8(env, password);
  return invoke(env, [&n, &e, &p](char **out, char **err) {
    return newpgp_generate_identity(n.c_str(), e.c_str(), p.c_str(), out, err);
  });
}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_publicKey(JNIEnv *env, jclass) {
  return invoke(env, [](char **out, char **err) { return newpgp_public_key(out, err); });
}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_encrypt(JNIEnv *env, jclass,
                                              jstring plaintext, jstring recipients_csv,
                                              jboolean sign, jstring password) {
  const std::string text = jstringToUtf8(env, plaintext);
  const std::string recipients = jstringToUtf8(env, recipients_csv);
  const std::string p = jstringToUtf8(env, password);
  const uint8_t sign_flag = (sign == JNI_TRUE) ? 1U : 0U;
  return invoke(env, [&text, &recipients, &p, sign_flag](char **out, char **err) {
    return newpgp_encrypt(text.c_str(), recipients.c_str(), sign_flag, p.c_str(), out, err);
  });
}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_decrypt(JNIEnv *env, jclass,
                                              jstring armored, jstring password) {
  const std::string message = jstringToUtf8(env, armored);
  const std::string p = jstringToUtf8(env, password);
  return invoke(env, [&message, &p](char **out, char **err) {
    return newpgp_decrypt(message.c_str(), p.c_str(), out, err);
  });
}

JNIEXPORT jstring JNICALL
Java_com_newpgp_keyboard_NewPgpNative_safetyNumber(JNIEnv *env, jclass, jstring email) {
  const std::string e = jstringToUtf8(env, email);
  return invoke(env, [&e](char **out, char **err) {
    return newpgp_safety_number(e.c_str(), out, err);
  });
}

}  // extern "C"
