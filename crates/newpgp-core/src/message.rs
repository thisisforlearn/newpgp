// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Text and arbitrary-byte encryption with OpenPGP.

use std::io::{Cursor, Write};

use pgp::armor::{write as armor_write, BlockType};
use pgp::composed::{Message, MessageBuilder, SignedPublicKey, SignedSecretKey};
use pgp::crypto::aead::{AeadAlgorithm, ChunkSize};
use pgp::crypto::hash::HashAlgorithm;
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use pgp::ser::Serialize;
use pgp::types::{CompressionAlgorithm, Password, VerifyingKey};
use rand::rngs::OsRng;

use crate::armor::normalize_armor;
use crate::error::{pgp_error, Error, Result};
use crate::keys::{encryption_subkey, format_fingerprint};

/// Wraps already-serialized OpenPGP packets for the armor writer.
struct RawBytes(Vec<u8>);

impl Serialize for RawBytes {
    fn to_writer<W: Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(&self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

/// How the encrypted container is built.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum EncryptionProfile {
    /// SEIPDv1 + AES-256. Readable by essentially every OpenPGP client.
    #[default]
    Compatible,
    /// SEIPDv2 + AES-256 + OCB AEAD (RFC 9580). Stronger, but older clients
    /// may not be able to read it.
    Aead,
}

/// The result of encrypting something.
#[derive(Debug, Clone)]
pub struct Encrypted {
    /// Raw OpenPGP bytes, for attaching to a file or embedding in MIME.
    pub binary: Vec<u8>,
    /// ASCII-armored text, safe to paste into email or chat.
    pub armored: String,
}

/// Encrypt bytes to one or more recipients, optionally signing them.
///
/// `signer` is `(secret key, password for that key)`. Pass an empty password
/// if the key is unprotected.
pub fn encrypt_bytes(
    plaintext: &[u8],
    recipients: &[SignedPublicKey],
    signer: Option<(&SignedSecretKey, &str)>,
    filename: &str,
    profile: EncryptionProfile,
) -> Result<Encrypted> {
    if recipients.is_empty() {
        return Err(
            Error::new("no_recipient", "There is nobody to encrypt this for.")
                .with_hint("Add a recipient, or choose 'encrypt with a password' instead."),
        );
    }

    let mut rng = OsRng;
    let mut builder = MessageBuilder::from_bytes(filename.to_string(), plaintext.to_vec());
    builder.compression(CompressionAlgorithm::ZLIB);

    // The two container versions have different Rust types, so the shared
    // sign/recipient/finalize steps live in a small macro.
    macro_rules! finish {
        ($builder:expr) => {{
            let mut builder = $builder;
            if let Some((secret, password)) = signer {
                builder.sign(
                    &secret.primary_key,
                    Password::from(password),
                    HashAlgorithm::Sha256,
                );
            }
            for recipient in recipients {
                builder
                    .encrypt_to_key(&mut rng, encryption_subkey(recipient)?)
                    .map_err(pgp_error("Could not encrypt for one of the recipients"))?;
            }
            builder
                .to_vec(&mut rng)
                .map_err(pgp_error("Could not finish encryption"))?
        }};
    }

    let binary = match profile {
        EncryptionProfile::Compatible => {
            finish!(builder.seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256))
        }
        EncryptionProfile::Aead => finish!(builder.seipd_v2(
            &mut rng,
            SymmetricKeyAlgorithm::AES256,
            AeadAlgorithm::Ocb,
            ChunkSize::default(),
        )),
    };

    // Armor the exact bytes we just produced, so the binary and text forms can
    // never disagree.
    let mut armored = Vec::new();
    armor_write(
        &RawBytes(binary.clone()),
        BlockType::Message,
        &mut armored,
        None,
        true,
    )
    .map_err(pgp_error("Could not format the encrypted message"))?;
    let armored = String::from_utf8(armored).map_err(|_| {
        Error::new(
            "armor_utf8",
            "Could not format the encrypted message as text.",
        )
    })?;

    Ok(Encrypted { binary, armored })
}

/// The outcome of a signature check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SignatureStatus {
    /// The message was not signed.
    Unsigned,
    /// A valid signature from a key we know.
    Verified(String),
    /// A signature was present but none of the supplied keys matched it.
    Unverified,
    /// A signature was present and matched a supplied key, but was invalid.
    Invalid,
}

impl SignatureStatus {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Unsigned => "Not signed",
            Self::Verified(_) => "Verified",
            Self::Unverified => "Signature from an unknown key",
            Self::Invalid => "Invalid signature — treat this message with care",
        }
    }
}

/// A decrypted message.
#[derive(Debug, Clone)]
pub struct Decrypted {
    pub data: Vec<u8>,
    pub signature: SignatureStatus,
}

/// Decrypt text or bytes, tolerating mangled armor.
///
/// `known_keys` are the public keys we will try when checking a signature.
/// Supplying your contacts' keys is what turns "some signature" into
/// "signed by Alice".
pub fn decrypt_bytes(
    armored_or_binary: &[u8],
    secret: &SignedSecretKey,
    key_password: &str,
    known_keys: &[SignedPublicKey],
) -> Result<Decrypted> {
    let armored_text = std::str::from_utf8(armored_or_binary)
        .ok()
        .filter(|text| text.contains("-----BEGIN PGP "))
        .map(normalize_armor)
        .transpose()?;

    let message = match &armored_text {
        Some(text) => Message::from_armor(Cursor::new(text.as_bytes()))
            .map(|(message, _)| message)
            .map_err(pgp_error("This doesn't look like an encrypted PGP message"))?,
        None => Message::from_bytes(armored_or_binary)
            .map_err(pgp_error("This doesn't look like an encrypted PGP message"))?,
    };

    let mut message = message
        .decrypt(&Password::from(key_password), secret)
        .map_err(|e| {
            let text = e.to_string().to_ascii_lowercase();
            if text.contains("password") || text.contains("passphrase") {
                Error::new(
                    "wrong_key_password",
                    "That key is locked with a different password.",
                )
                .with_hint("Try the password you chose when you created your key.")
            } else {
                Error::new(
                    "wrong_secret_key",
                    "This message wasn't encrypted for the key you selected.",
                )
                .with_hint("Check which identity is active in the app.")
            }
        })?;

    if message.is_compressed() {
        message = message
            .decompress()
            .map_err(pgp_error("Could not unpack the message"))?;
    }

    let data = message.as_data_vec().map_err(|e| {
        Error::new("read_contents", "Could not read the decrypted contents.")
            .with_hint(e.to_string())
    })?;

    let signature = verify_signature(&message, known_keys);
    Ok(Decrypted { data, signature })
}

fn verify_signature(message: &Message<'_>, known_keys: &[SignedPublicKey]) -> SignatureStatus {
    let mut had_signature = false;
    let mut invalid = false;
    for key in known_keys {
        match message.verify(&key.primary_key as &dyn VerifyingKey) {
            Ok(_) => {
                return SignatureStatus::Verified(format_fingerprint(&key.primary_key));
            }
            Err(_) => {
                had_signature = true;
                invalid = true;
            }
        }
    }
    if invalid && had_signature {
        // A failed verify can also mean "not signed at all". We cannot tell
        // without inspecting packets, so call it unverified rather than crying wolf.
        SignatureStatus::Unverified
    } else {
        SignatureStatus::Unsigned
    }
}

/// Convenience wrapper for UTF-8 messages.
pub fn decrypt_text(
    armored_or_binary: &[u8],
    secret: &SignedSecretKey,
    key_password: &str,
    known_keys: &[SignedPublicKey],
) -> Result<(String, SignatureStatus)> {
    let decrypted = decrypt_bytes(armored_or_binary, secret, key_password, known_keys)?;
    let text = String::from_utf8(decrypted.data)
        .map_err(|_| Error::new("not_text", "This encrypted file isn't plain text."))?;
    Ok((text, decrypted.signature))
}

/// Encrypt a UTF-8 message and return a paste-ready armored block.
pub fn encrypt_text(
    text: &str,
    recipients: &[SignedPublicKey],
    signer: Option<(&SignedSecretKey, &str)>,
    profile: EncryptionProfile,
) -> Result<String> {
    Ok(encrypt_bytes(text.as_bytes(), recipients, signer, "message.txt", profile)?.armored)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::generate_identity;

    #[test]
    fn text_roundtrip_with_signature() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let bob = generate_identity("Bob", "bob@example.com", "").unwrap();
        let armored = encrypt_text(
            "Hello Bob, this is a test.",
            std::slice::from_ref(&bob.public),
            Some((&alice.secret, "")),
            EncryptionProfile::Compatible,
        )
        .unwrap();
        assert!(armored.starts_with("-----BEGIN PGP MESSAGE-----"));

        let (plain, status) = decrypt_text(
            armored.as_bytes(),
            &bob.secret,
            "",
            std::slice::from_ref(&alice.public),
        )
        .unwrap();
        assert_eq!(plain, "Hello Bob, this is a test.");
        assert_eq!(
            status,
            SignatureStatus::Verified(crate::keys::format_fingerprint(&alice.public.primary_key))
        );
    }

    #[test]
    fn aead_profile_roundtrips() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let armored = encrypt_text(
            "AEAD test",
            std::slice::from_ref(&alice.public),
            None,
            EncryptionProfile::Aead,
        )
        .unwrap();
        let (plain, _) = decrypt_text(armored.as_bytes(), &alice.secret, "", &[]).unwrap();
        assert_eq!(plain, "AEAD test");
    }

    #[test]
    fn mangled_armor_still_decrypts() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let armored = encrypt_text(
            "quoted",
            std::slice::from_ref(&alice.public),
            None,
            Default::default(),
        )
        .unwrap();
        let mangled = armored
            .lines()
            .map(|l| format!("> {l}"))
            .collect::<Vec<_>>()
            .join("\n");
        let (plain, _) = decrypt_text(mangled.as_bytes(), &alice.secret, "", &[]).unwrap();
        assert_eq!(plain, "quoted");
    }

    #[test]
    fn wrong_key_gives_a_friendly_error() {
        let bob = generate_identity("Bob", "bob@example.com", "").unwrap();
        let eve = generate_identity("Eve", "eve@example.com", "").unwrap();
        let armored = encrypt_text(
            "secret",
            std::slice::from_ref(&bob.public),
            None,
            Default::default(),
        )
        .unwrap();
        let err = decrypt_text(armored.as_bytes(), &eve.secret, "", &[]).unwrap_err();
        assert_eq!(err.code, "wrong_secret_key");
        assert!(err.friendly().contains("identity"));
    }
}
