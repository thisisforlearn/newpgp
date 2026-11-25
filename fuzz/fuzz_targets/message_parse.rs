// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
#![no_main]

use libfuzzer_sys::fuzz_target;
use newpgp_core::keys::{parse_public_key, parse_secret_key};
use newpgp_core::message::decrypt_bytes;
use pgp::composed::SignedSecretKey;

fuzz_target!(|input: &[u8]| {
    let _ = parse_public_key(input);
    let _ = parse_secret_key(input);
    // Parsing hostile data before any key is available must fail gracefully.
    let dummy: Option<&SignedSecretKey> = None;
    if let Some(secret) = dummy {
        let _ = decrypt_bytes(input, secret, "", &[]);
    }
});
