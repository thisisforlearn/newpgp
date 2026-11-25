// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Key generation, parsing and human-friendly identifiers.

use std::io::Cursor;

use pgp::composed::{
    ArmorOptions, Deserializable, EncryptionCaps, KeyType, SecretKeyParamsBuilder, SignedPublicKey,
    SignedSecretKey, SubkeyParamsBuilder,
};
use pgp::crypto::ecc_curve::ECCCurve;
use pgp::types::KeyDetails;
use rand::rngs::OsRng;
use sha2::{Digest, Sha256};

use crate::error::{pgp_error, Error, Result};

/// An OpenPGP identity: a signing primary key and a separate encryption subkey.
pub struct Identity {
    pub secret: SignedSecretKey,
    pub public: SignedPublicKey,
    pub fingerprint: String,
}

impl Identity {
    /// ASCII-armored public key, safe to share with anyone.
    pub fn public_armor(&self) -> Result<String> {
        self.public
            .to_armored_string(ArmorOptions::default())
            .map_err(pgp_error("Could not export the public key"))
    }

    /// ASCII-armored secret key. Treat this like a password.
    pub fn secret_armor(&self) -> Result<String> {
        self.secret
            .to_armored_string(ArmorOptions::default())
            .map_err(pgp_error("Could not export the secret key"))
    }
}

/// Generate a v4 Ed25519 + Curve25519 OpenPGP identity.
///
/// `password` may be empty. When present, the secret key packets are protected
/// with OpenPGP's standard iterated-and-salted string-to-key derivation (S2K).
pub fn generate_identity(name: &str, email: &str, password: &str) -> Result<Identity> {
    if name.trim().is_empty() {
        return Err(Error::new("missing_name", "Please enter your name."));
    }
    if !email.contains('@') || email.starts_with('@') || email.ends_with('@') {
        return Err(
            Error::new("bad_email", "That email address doesn't look right.").with_hint(
                "It should look like name@example.com so people can find your key automatically.",
            ),
        );
    }

    let rng = OsRng;
    let user_id = format!("{} <{}>", name.trim(), email.trim());

    let mut enc_subkey = SubkeyParamsBuilder::default();
    enc_subkey
        .key_type(KeyType::ECDH(ECCCurve::Curve25519Legacy))
        .can_sign(false)
        .can_encrypt(EncryptionCaps::All)
        .can_authenticate(false);
    let enc_subkey = enc_subkey
        .build()
        .map_err(|_| Error::new("keygen", "Could not configure the encryption subkey."))?;

    let mut params = SecretKeyParamsBuilder::default();
    params
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .can_sign(true)
        .can_encrypt(EncryptionCaps::None)
        .primary_user_id(user_id)
        .subkeys(vec![enc_subkey]);
    if !password.is_empty() {
        params.passphrase(Some(password.to_string()));
    }

    let params = params
        .build()
        .map_err(|e| Error::new("keygen", format!("Could not prepare the key: {e}")))?;
    let secret = params
        .generate(rng)
        .map_err(pgp_error("Could not generate the key"))?;
    secret
        .verify_bindings()
        .map_err(pgp_error("The generated key failed its self-check"))?;

    let fingerprint = format_fingerprint(&secret.primary_key);
    let public = SignedPublicKey::from(secret.clone());

    Ok(Identity {
        secret,
        public,
        fingerprint,
    })
}

/// Parse a public key from armor or binary data.
pub fn parse_public_key(input: &[u8]) -> Result<SignedPublicKey> {
    let text = String::from_utf8_lossy(input);
    let parsed = if text.contains("-----BEGIN PGP ") {
        SignedPublicKey::from_armor_single(Cursor::new(input))
            .map(|(key, _)| key)
            .or_else(|_| SignedPublicKey::from_bytes(input))
    } else {
        SignedPublicKey::from_bytes(input)
    }
    .map_err(pgp_error("I couldn't read that public key"))?;

    parsed
        .verify_bindings()
        .map_err(pgp_error("That public key has invalid internal signatures"))?;
    Ok(parsed)
}

/// Parse a secret key from armor or binary data.
pub fn parse_secret_key(input: &[u8]) -> Result<SignedSecretKey> {
    let text = String::from_utf8_lossy(input);
    let parsed = if text.contains("-----BEGIN PGP ") {
        SignedSecretKey::from_armor_single(Cursor::new(input))
            .map(|(key, _)| key)
            .or_else(|_| SignedSecretKey::from_bytes(input))
    } else {
        SignedSecretKey::from_bytes(input)
    }
    .map_err(pgp_error("I couldn't read that secret key"))?;

    parsed
        .verify_bindings()
        .map_err(pgp_error("That secret key has invalid internal signatures"))?;
    Ok(parsed)
}

/// A spaced-out fingerprint, which is much easier to read aloud.
pub fn format_fingerprint(key: &dyn KeyDetails) -> String {
    let hex = hex::encode(key.fingerprint().as_bytes()).to_ascii_uppercase();
    hex.as_bytes()
        .chunks(4)
        .map(|c| std::str::from_utf8(c).unwrap_or(""))
        .collect::<Vec<_>>()
        .join(" ")
}

/// The unspaced uppercase fingerprint, used in invite links and safety numbers.
pub fn raw_fingerprint(key: &dyn KeyDetails) -> String {
    hex::encode(key.fingerprint().as_bytes()).to_ascii_uppercase()
}

/// Find the first subkey that can be used for encryption.
///
/// Traditional PGP clients disagree about subkey selection, which is why users
/// often see "cannot select recipient". We make the choice deterministic and
/// explain it if no suitable subkey exists.
pub fn encryption_subkey(cert: &SignedPublicKey) -> Result<&pgp::composed::SignedPublicSubKey> {
    cert.public_subkeys
        .iter()
        .find(|sub| sub.algorithm().can_encrypt())
        .ok_or_else(|| {
            Error::new(
                "no_encryption_subkey",
                "This person's key can't be used for encryption.",
            )
            .with_hint("Ask them to publish a newer key with an encryption subkey.")
        })
}

/// A short code that two people can compare out of band to confirm they really
/// have each other's key. This is a simplified, transparent derivation: sort
/// both fingerprints, hash them, and render the first 160 bits as digit groups.
///
/// It is intentionally NOT Signal-compatible; it is a verification aid, not a
/// secret. Comparing it out of band is what defeats a fake key swap.
pub fn safety_number(a: &dyn KeyDetails, b: &dyn KeyDetails) -> String {
    let mut fa = raw_fingerprint(a);
    let mut fb = raw_fingerprint(b);
    if fa > fb {
        std::mem::swap(&mut fa, &mut fb);
    }
    let mut hasher = Sha256::new();
    hasher.update(fa.as_bytes());
    hasher.update(b"\x00");
    hasher.update(fb.as_bytes());
    let digest = hasher.finalize();

    let mut value = 0u128;
    for byte in digest.iter().take(16) {
        value = (value << 8) | (*byte as u128);
    }

    let mut groups = Vec::with_capacity(12);
    for _ in 0..12 {
        groups.push(format!("{:04}", value % 10_000));
        value /= 10_000;
    }
    groups.reverse();
    groups.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_an_identity_and_roundtrips_armor() {
        let id = generate_identity("Alice Example", "alice@example.com", "").unwrap();
        assert_eq!(id.fingerprint.len(), 49); // 40 hex chars + 9 spaces
        let pub_armor = id.public_armor().unwrap();
        assert!(pub_armor.starts_with("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
        let parsed = parse_public_key(pub_armor.as_bytes()).unwrap();
        assert_eq!(raw_fingerprint(&parsed), raw_fingerprint(&id.public));
        assert!(encryption_subkey(&parsed).is_ok());
    }

    #[test]
    fn password_protected_key_roundtrips() {
        let id =
            generate_identity("Bob Example", "bob@example.com", "correct horse battery").unwrap();
        let armor = id.secret_armor().unwrap();
        let parsed = parse_secret_key(armor.as_bytes()).unwrap();
        assert_eq!(
            raw_fingerprint(&parsed.primary_key),
            raw_fingerprint(&id.secret.primary_key)
        );
    }

    #[test]
    fn rejects_bad_email() {
        assert!(generate_identity("A", "not-an-email", "").is_err());
    }

    #[test]
    fn safety_number_is_order_independent_and_stable() {
        let a = generate_identity("Alice", "alice@example.com", "").unwrap();
        let b = generate_identity("Bob", "bob@example.com", "").unwrap();
        let ab = safety_number(&a.public, &b.public);
        let ba = safety_number(&b.public, &a.public);
        assert_eq!(ab, ba);
        assert_eq!(ab.split_whitespace().count(), 12);
    }
}
