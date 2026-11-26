// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Minimal C ABI consumed by the Android JNI shim.
//!
//! Every call has the shape `fn(..., out: *mut *mut c_char, err: *mut *mut c_char) -> u8`.
//! `1` means `*out` holds a NUL-terminated UTF-8 string; `0` means `*err` holds
//! a plain-language error. The caller must free either pointer with
//! `newpgp_free_string`. Nothing is logged, and no Rust error type crosses
//! this boundary.

use std::ffi::{CStr, CString, c_char};

use newpgp_core::Result;

/// # Safety
/// `p` must be null or point to a NUL-terminated C string valid for this call.
unsafe fn read_cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    CStr::from_ptr(p).to_string_lossy().into_owned()
}

/// # Safety
/// `err` must be null or a valid writable pointer.
unsafe fn write_error(message: &str, err: *mut *mut c_char) {
    if err.is_null() {
        return;
    }
    let safe: String = message.chars().map(|c| if c == '\0' { ' ' } else { c }).collect();
    let c = CString::new(safe)
        .unwrap_or_else(|_| CString::new("Something went wrong.").expect("literal is valid"));
    *err = c.into_raw();
}

/// # Safety
/// `out`/`err` must be null or valid writable pointers. Exactly one is written.
unsafe fn finish(result: Result<String>, out: *mut *mut c_char, err: *mut *mut c_char) -> u8 {
    match result {
        Ok(value) => match CString::new(value) {
            Ok(c) => {
                if !out.is_null() {
                    *out = c.into_raw();
                }
                1
            }
            Err(_) => {
                write_error("The result could not be converted for the keyboard.", err);
                0
            }
        },
        Err(error) => {
            write_error(&error.friendly(), err);
            0
        }
    }
}

/// # Safety
/// `out`/`err` must be null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_init(
    files_dir_abs: *const c_char,
    out: *mut *mut c_char,
    err: *mut *mut c_char,
) -> u8 {
    let path = read_cstr(files_dir_abs);
    finish(crate::init(&path).map(|_| String::new()), out, err)
}

/// # Safety
/// `out`/`err` must be null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_version(out: *mut *mut c_char, err: *mut *mut c_char) -> u8 {
    finish(Ok(crate::version()), out, err)
}

/// # Safety
/// All string pointers must be null or valid NUL-terminated C strings;
/// `out`/`err` must be null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_generate_identity(
    name: *const c_char,
    email: *const c_char,
    password: *const c_char,
    out: *mut *mut c_char,
    err: *mut *mut c_char,
) -> u8 {
    let name = read_cstr(name);
    let email = read_cstr(email);
    let password = read_cstr(password);
    finish(crate::generate_identity(&name, &email, &password), out, err)
}

/// # Safety
/// `out`/`err` must be null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_public_key(out: *mut *mut c_char, err: *mut *mut c_char) -> u8 {
    finish(crate::public_key(), out, err)
}

/// # Safety
/// All string pointers must be null or valid NUL-terminated C strings;
/// `out`/`err` must be null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_encrypt(
    plaintext: *const c_char,
    recipients_csv: *const c_char,
    sign: u8,
    password: *const c_char,
    out: *mut *mut c_char,
    err: *mut *mut c_char,
) -> u8 {
    let plaintext = read_cstr(plaintext);
    let recipients = read_cstr(recipients_csv);
    let password = read_cstr(password);
    finish(
        crate::encrypt(&plaintext, &recipients, sign != 0, &password),
        out,
        err,
    )
}

/// # Safety
/// All string pointers must be null or valid NUL-terminated C strings;
/// `out`/`err` must be null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_decrypt(
    armored: *const c_char,
    password: *const c_char,
    out: *mut *mut c_char,
    err: *mut *mut c_char,
) -> u8 {
    let armored = read_cstr(armored);
    let password = read_cstr(password);
    finish(crate::decrypt(&armored, &password), out, err)
}

/// # Safety
/// `email` must be null or a valid NUL-terminated C string; `out`/`err` must be
/// null or valid writable pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_safety_number(
    email: *const c_char,
    out: *mut *mut c_char,
    err: *mut *mut c_char,
) -> u8 {
    let email = read_cstr(email);
    finish(crate::safety_number(&email), out, err)
}

/// # Safety
/// `s` must be null or a pointer previously returned in `*out`/`*err`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn newpgp_free_string(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}
