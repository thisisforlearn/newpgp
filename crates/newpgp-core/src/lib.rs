// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! # newpgp-core
//!
//! Friendly OpenPGP primitives shared by every NewPGP app.
//!
//! This crate deliberately exposes a small, opinionated API rather than the
//! full rPGP surface. The goal is that a GUI developer cannot accidentally
//! choose a broken subkey or skip signature verification.

pub mod armor;
pub mod error;
pub mod files;
pub mod keys;
pub mod message;

pub use error::{Error, Result};
pub use keys::Identity;
pub use message::{
    decrypt_bytes, decrypt_text, encrypt_bytes, encrypt_text, Decrypted, Encrypted,
    EncryptionProfile, SignatureStatus,
};

/// The OpenPGP implementation and RFC this build targets.
pub const OPENPGP_IMPLEMENTATION: &str = "rPGP 0.21 (pure Rust)";
pub const OPENPGP_SPEC: &str = "RFC 9580";
