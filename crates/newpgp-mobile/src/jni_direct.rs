// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Optional direct-JNI exports built with the `jni` crate.
//!
//! The shipped Android library uses the plain-JNI C++ shim: Rust exposes a C
//! ABI (`ffi`) and the shim owns string conversion and Java exceptions. That
//! keeps dependencies and APK size minimal and gives us the exact symbol names
//! Kotlin expects.
//!
//! This module is an alternative, enabled with
//! `--features direct-jni --target aarch64-linux-android`. It exports the same
//! eight symbols straight from Rust using the `jni` crate. Do **not** link this
//! into the same shared object as the C++ shim: the symbols would collide. It
//! exists for maintainers who want to drop the C++ layer, and so that host
//! `cargo check` has a matching non-Android stub.

#[cfg(all(target_os = "android", feature = "direct-jni"))]
pub(crate) const DIRECT_JNI: bool = true;

#[cfg(not(all(target_os = "android", feature = "direct-jni")))]
pub(crate) const DIRECT_JNI: bool = false;

#[cfg(all(target_os = "android", feature = "direct-jni"))]
mod android {
    use std::ptr;

    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jstring};
    use jni::JNIEnv;

    use newpgp_core::Result;

    /// Read a Kotlin `String` without logging its contents.
    fn arg(env: &mut JNIEnv<'_>, value: &JString<'_>) -> String {
        match env.get_string(value) {
            Ok(text) => String::from(text),
            Err(_) => String::new(),
        }
    }

    /// Return a Java string, or throw a plain-language `RuntimeException`.
    fn result(env: &mut JNIEnv<'_>, value: Result<String>) -> jstring {
        match value {
            Ok(text) => env
                .new_string(text)
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut()),
            Err(error) => {
                let message: String = error
                    .friendly()
                    .chars()
                    .map(|c| if c == '\0' { ' ' } else { c })
                    .collect();
                let _ = env.throw_new("java/lang/RuntimeException", message);
                ptr::null_mut()
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_version(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
    ) -> jstring {
        result(&mut env, Ok(crate::version()))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_init(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        files_dir_abs: JString<'_>,
    ) -> jstring {
        let path = arg(&mut env, &files_dir_abs);
        result(&mut env, crate::init(&path).map(|_| String::new()))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_generateIdentity(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        name: JString<'_>,
        email: JString<'_>,
        password: JString<'_>,
    ) -> jstring {
        let name = arg(&mut env, &name);
        let email = arg(&mut env, &email);
        let password = arg(&mut env, &password);
        result(&mut env, crate::generate_identity(&name, &email, &password))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_publicKey(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
    ) -> jstring {
        result(&mut env, crate::public_key())
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_encrypt(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        plaintext: JString<'_>,
        recipients_csv: JString<'_>,
        sign: jboolean,
        password: JString<'_>,
    ) -> jstring {
        let plaintext = arg(&mut env, &plaintext);
        let recipients = arg(&mut env, &recipients_csv);
        let password = arg(&mut env, &password);
        result(
            &mut env,
            crate::encrypt(&plaintext, &recipients, sign != 0, &password),
        )
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_decrypt(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        armored: JString<'_>,
        password: JString<'_>,
    ) -> jstring {
        let armored = arg(&mut env, &armored);
        let password = arg(&mut env, &password);
        result(&mut env, crate::decrypt(&armored, &password))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_newpgp_keyboard_NewPgpNative_safetyNumber(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        email: JString<'_>,
    ) -> jstring {
        let email = arg(&mut env, &email);
        result(&mut env, crate::safety_number(&email))
    }
}
