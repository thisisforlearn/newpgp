// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! # newpgp-mobile
//!
//! The real on-device OpenPGP bridge for the NewPGP Android keyboard.
//!
//! This crate is deliberately thin: every cryptographic operation delegates to
//! `newpgp-core`, and contact trust is delegated to `newpgp-keyring`. There is
//! no custom crypto here, no network access, and no logging of text, keys or
//! passwords. Kotlin talks to it through a small C ABI (`ffi`) wrapped by the
//! JNI shim in `android/keyboard/app/src/main/cpp`.
//!
//! Why plain JNI instead of UniFFI: UniFFI would add the `uniffi` scaffolding
//! dependency, a `uniffi-bindgen` code-generation step, JNA and coroutines to
//! a keyboard APK for a fixed, eight-function, string-in/string-out ABI. A
//! hand-written JNI shim has zero extra runtime dependencies, keeps the exact
//! exported names the Kotlin object expects, and makes exception/error text
//! fully controllable. See README.md for the full tradeoff and build notes.

mod ffi;
mod jni_direct;
mod store;

use newpgp_core::keys::{
    generate_identity as core_generate_identity, parse_public_key, parse_secret_key,
};
use newpgp_core::message::{decrypt_text, encrypt_text, EncryptionProfile, SignatureStatus};
use newpgp_core::{Error, Result};
use newpgp_keyring::{KeyRing, KeySource, TofuOutcome};
use pgp::composed::SignedPublicKey;
use serde_json::json;

pub use store::init;

/// A stable, user-visible version string (no device or path information).
pub fn version() -> String {
    let base = format!(
        "newpgp-mobile {} | {} | {}",
        env!("CARGO_PKG_VERSION"),
        newpgp_core::OPENPGP_IMPLEMENTATION,
        newpgp_core::OPENPGP_SPEC
    );
    if jni_direct::DIRECT_JNI {
        format!("{base} | direct-jni")
    } else {
        base
    }
}

/// Create an identity and store it in app-private files.
///
/// Returns JSON: `{"fingerprint": "...", "publicKey": "...", "privateKey": "..."}`.
/// The private key armor may be protected with `password`; an empty password
/// means the key is stored unencrypted inside the app sandbox (see README.md).
pub fn generate_identity(name: &str, email: &str, password: &str) -> Result<String> {
    let identity = core_generate_identity(name, email, password)?;

    let stored = store::StoredIdentity {
        email: email.trim().to_string(),
        fingerprint: identity.fingerprint.clone(),
        public_key: identity.public_armor()?,
        private_key: identity.secret_armor()?,
    };
    store::save_identity(&stored)?;

    // Remember our own key so two-sided safety numbers work, and learn the
    // address as a contact so `safetyNumber(ownEmail)` is meaningful too.
    if let Ok(mut ring) = store::keyring() {
        let _ = ring.set_own_identity(&identity.public);
        let _ = ring.learn(email, &identity.public, KeySource::Manual);
        let _ = ring.save();
    }

    Ok(json!({
        "fingerprint": identity.fingerprint,
        "publicKey": stored.public_key,
        "privateKey": stored.private_key,
    })
    .to_string())
}

/// ASCII-armored public key of the active identity, or `""` when none exists.
pub fn public_key() -> Result<String> {
    match store::load_identity() {
        Ok(identity) => Ok(identity.public_key),
        // An absent identity is a normal state, not a failure: the keyboard
        // shows its "no key yet" UI instead of an exception.
        Err(err) if err.code == "no_identity" => Ok(String::new()),
        Err(err) => Err(err),
    }
}

/// Encrypt UTF-8 text for one or more recipients.
///
/// `recipients_csv` may contain email addresses known to the local keyring
/// and/or ASCII-armored public keys. `sign` adds a signature from the active
/// identity using `password` as that key's passphrase.
pub fn encrypt(
    plaintext: &str,
    recipients_csv: &str,
    sign: bool,
    password: &str,
) -> Result<String> {
    let mut ring = store::keyring()?;
    let recipients = resolve_recipients(recipients_csv, &mut ring)?;

    let signer = if sign {
        let identity = store::load_identity()?;
        let secret = parse_secret_key(identity.private_key.as_bytes())?;
        Some((secret, password.to_string()))
    } else {
        None
    };

    let signer_ref = signer
        .as_ref()
        .map(|(secret, password)| (secret, password.as_str()));

    // SEIPDv1 + AES-256 is readable by essentially every OpenPGP client, which
    // matters for a keyboard that pastes into arbitrary chat and mail apps.
    encrypt_text(
        plaintext,
        &recipients,
        signer_ref,
        EncryptionProfile::Compatible,
    )
}

/// Decrypt an armored message with the active identity.
///
/// Returns JSON: `{"plaintext": "...", "signature": "verified|unverified|unsigned"}`.
pub fn decrypt(armored: &str, password: &str) -> Result<String> {
    let identity = store::load_identity()?;
    let secret = parse_secret_key(identity.private_key.as_bytes())?;

    let mut known_keys = Vec::new();
    if let Ok(own) = parse_public_key(identity.public_key.as_bytes()) {
        known_keys.push(own);
    }
    if let Ok(ring) = store::keyring() {
        for contact in ring.contacts() {
            if let Ok(key) = contact.public_key() {
                known_keys.push(key);
            }
        }
    }

    let (plaintext, status) = decrypt_text(armored.as_bytes(), &secret, password, &known_keys)?;
    let signature = match status {
        SignatureStatus::Unsigned => "unsigned",
        SignatureStatus::Verified(_) => "verified",
        // The contract exposes exactly three states. An invalid signature from
        // a known key is deliberately reported as "unverified" so a caller can
        // never mistake it for trustworthy.
        SignatureStatus::Unverified | SignatureStatus::Invalid => "unverified",
    };

    Ok(json!({ "plaintext": plaintext, "signature": signature }).to_string())
}

/// A short code to compare out of band for a contact's key.
pub fn safety_number(email: &str) -> Result<String> {
    let ring = store::keyring()?;
    ring.safety_number_for(email)
}

/// Turn the comma-separated recipient field into parsed public keys.
fn resolve_recipients(recipients_csv: &str, ring: &mut KeyRing) -> Result<Vec<SignedPublicKey>> {
    let mut recipients = Vec::new();
    let mut learned = false;

    for raw in recipients_csv.split(',') {
        let token = raw.trim();
        if token.is_empty() {
            continue;
        }

        let key = if token.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") {
            let key = parse_public_key(token.as_bytes())?;
            // TOFU: remember an address found in the key so it can be used by
            // email later and so safety numbers are available. A key change is
            // parked as pending rather than silently trusted.
            if let Some(email) = first_email(&key) {
                match ring.learn(&email, &key, KeySource::Manual) {
                    Ok(TofuOutcome::KeyChanged { .. }) => {}
                    Ok(_) => learned = true,
                    Err(_) => {}
                }
            }
            key
        } else {
            let contact = ring.get(&KeyRing::canonical_email(token)).ok_or_else(|| {
                Error::new(
                    "unknown_recipient",
                    format!("I don't have a key for {token} yet."),
                )
                .with_hint("Paste their public key instead, or add them in settings.")
            })?;
            contact.public_key()?
        };
        recipients.push(key);
    }

    if recipients.is_empty() {
        return Err(Error::new(
            "no_recipient",
            "There is nobody to encrypt this for.",
        )
        .with_hint("Add a recipient or paste their public key first."));
    }
    if learned {
        let _ = ring.save();
    }
    Ok(recipients)
}

/// Extract the first `name <email>` address from a certificate's user IDs.
fn first_email(key: &SignedPublicKey) -> Option<String> {
    for user in &key.details.users {
        let Some(id) = user.id.as_str() else { continue };
        if let (Some(open), Some(close)) = (id.find('<'), id.find('>')) {
            if close > open + 1 {
                let email = id[open + 1..close].trim();
                if email.contains('@') {
                    return Some(email.to_string());
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_offline_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        init(dir.path().to_str().unwrap()).unwrap();

        let generated = generate_identity("Alice Example", "alice@example.com", "").unwrap();
        let value: serde_json::Value = serde_json::from_str(&generated).unwrap();
        assert!(value["publicKey"]
            .as_str()
            .unwrap()
            .starts_with("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
        assert!(value["privateKey"]
            .as_str()
            .unwrap()
            .starts_with("-----BEGIN PGP PRIVATE KEY BLOCK-----"));

        assert_eq!(
            public_key().unwrap(),
            value["publicKey"].as_str().unwrap()
        );

        let armored = encrypt("hello from the keyboard", "alice@example.com", true, "").unwrap();
        assert!(armored.starts_with("-----BEGIN PGP MESSAGE-----"));

        let result: serde_json::Value =
            serde_json::from_str(&decrypt(&armored, "").unwrap()).unwrap();
        assert_eq!(result["plaintext"], "hello from the keyboard");
        assert_eq!(result["signature"], "verified");

        let code = safety_number("alice@example.com").unwrap();
        assert_eq!(code.split_whitespace().count(), 12);
    }

    #[test]
    fn no_identity_returns_empty_public_key() {
        let dir = tempfile::tempdir().unwrap();
        init(dir.path().to_str().unwrap()).unwrap();
        assert_eq!(public_key().unwrap(), "");
    }
}
