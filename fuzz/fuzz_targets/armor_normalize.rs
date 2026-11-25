// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
#![no_main]

use libfuzzer_sys::fuzz_target;
use newpgp_core::armor::normalize_armor;

fuzz_target!(|input: &[u8]| {
    let text = String::from_utf8_lossy(input);
    // Must never panic, hang, or allocate unboundedly on hostile pasted text.
    let _ = normalize_armor(&text);
});
