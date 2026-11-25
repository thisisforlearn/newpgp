// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Tiny dependency-free benchmarks. Run with: cargo bench -p newpgp-core

use std::hint::black_box;
use std::time::Instant;

use newpgp_core::keys::generate_identity;
use newpgp_core::message::{decrypt_text, encrypt_text, EncryptionProfile};

fn main() {
    let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
    let bob = generate_identity("Bob", "bob@example.com", "").unwrap();
    let text = "A fairly ordinary email body. ".repeat(20);

    let start = Instant::now();
    for _ in 0..5 {
        black_box(generate_identity("Bench", "bench@example.com", "").unwrap());
    }
    println!("keygen (5x): {:?}", start.elapsed());

    let start = Instant::now();
    let armored = encrypt_text(
        &text,
        std::slice::from_ref(&bob.public),
        Some((&alice.secret, "")),
        EncryptionProfile::Compatible,
    )
    .unwrap();
    println!(
        "encrypt+sign: {:?} ({} bytes)",
        start.elapsed(),
        armored.len()
    );

    let start = Instant::now();
    for _ in 0..20 {
        let (plain, _) = decrypt_text(
            armored.as_bytes(),
            &bob.secret,
            "",
            std::slice::from_ref(&alice.public),
        )
        .unwrap();
        black_box(plain);
    }
    println!("decrypt+verify (20x): {:?}", start.elapsed());
}
