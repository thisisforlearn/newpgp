// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Key backups that can be transferred safely without meeting in person.
//!
//! Two modes:
//!
//! 1. **Public-key backup (recommended):** encrypt the secret key to a public
//!    key you already verified. No password has to travel anywhere.
//! 2. **Setup-code backup:** encrypt the secret key with a randomly generated
//!    36-digit code (Autocrypt `numeric9x4`, ~119 bits). Send the encrypted
//!    file through any channel; deliver the code by voice, SMS or QR — never
//!    in the same message. See docs/KEY_TRANSFER.md.

use std::io::Cursor;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use newpgp_core::armor::{normalize_armor, MESSAGE_BEGIN};
#[cfg(test)]
use newpgp_core::keys::raw_fingerprint;
use newpgp_core::keys::{parse_public_key, parse_secret_key};
use newpgp_core::{Error, Result};
use pgp::armor::{write as armor_write, BlockType};
use pgp::composed::{Message, MessageBuilder};
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use pgp::ser::Serialize;
use pgp::types::{CompressionAlgorithm, Password, StringToKey};
use rand::{rngs::OsRng, Rng};
use zeroize::Zeroizing;

pub const SETUP_CODE_GROUPS: usize = 9;
pub const SETUP_CODE_DIGITS: usize = 4;

/// Generate a CSPRNG setup code in Autocrypt's `numeric9x4` format.
pub fn generate_setup_code() -> Zeroizing<String> {
    let mut rng = OsRng;
    let mut groups = Vec::with_capacity(SETUP_CODE_GROUPS);
    for _ in 0..SETUP_CODE_GROUPS {
        let mut digits = String::with_capacity(SETUP_CODE_DIGITS);
        for _ in 0..SETUP_CODE_DIGITS {
            digits.push((rng.gen_range(0u8..10) + b'0') as char);
        }
        groups.push(digits);
    }
    Zeroizing::new(groups.join("-"))
}

/// Remove spaces and lowercase input, keeping the dashes (they are part of
/// the secret, exactly as Autocrypt specifies).
pub fn normalize_setup_code(input: &str) -> Zeroizing<String> {
    Zeroizing::new(
        input
            .trim()
            .chars()
            .filter(|c| c.is_ascii_digit() || *c == '-')
            .collect(),
    )
}

/// Validate code shape without revealing it in errors.
pub fn validate_setup_code(code: &str) -> Result<()> {
    let code = normalize_setup_code(code);
    let groups: Vec<&str> = code.split('-').collect();
    if groups.len() != SETUP_CODE_GROUPS
        || groups.iter().any(|g| g.len() != SETUP_CODE_DIGITS)
        || groups
            .iter()
            .any(|g| !g.chars().all(|c| c.is_ascii_digit()))
    {
        return Err(Error::new(
            "bad_setup_code",
            "That code should be 9 groups of 4 digits, separated by dashes.",
        )
        .with_hint("Look at the code you wrote down and try again."));
    }
    Ok(())
}

/// The first two digits can be shown in the armor header to reassure the user
/// and to make phishing harder, as Autocrypt recommends.
pub fn setup_code_prefix(code: &str) -> String {
    normalize_setup_code(code)
        .chars()
        .filter(|c| c.is_ascii_digit())
        .take(2)
        .collect()
}

/// Encrypt an armored secret key with a setup code using RFC 9580 Argon2id S2K.
pub fn create_setup_code_backup(secret_armor: &str, setup_code: &str) -> Result<String> {
    validate_setup_code(setup_code)?;
    let code = normalize_setup_code(setup_code);
    encrypt_with_code(secret_armor.as_bytes(), &code)
}

/// Restore a secret key from a setup-code backup.
pub fn restore_setup_code_backup(armored: &str, setup_code: &str) -> Result<String> {
    let code = normalize_setup_code(setup_code);
    let bytes = decrypt_with_code(armored, &code)?;
    let text = String::from_utf8(bytes)
        .map_err(|_| Error::new("bad_backup", "That backup did not contain a readable key."))?;
    parse_secret_key(text.as_bytes())?;
    Ok(text)
}

/// Encrypt a secret key to a recipient's public key. The recipient needs no
/// setup code at all — just their own key.
pub fn create_public_key_backup(secret_armor: &str, recipient_armor: &str) -> Result<String> {
    let recipient = parse_public_key(recipient_armor.as_bytes())?;
    let subkey = newpgp_core::keys::encryption_subkey(&recipient)?;
    let mut rng = OsRng;

    let mut builder =
        MessageBuilder::from_bytes("newpgp-backup.asc", secret_armor.as_bytes().to_vec());
    builder.compression(CompressionAlgorithm::ZLIB);
    let mut builder = builder.seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder.encrypt_to_key(&mut rng, subkey).map_err(|e| {
        Error::new("backup_encrypt", "Could not encrypt the backup.").with_hint(e.to_string())
    })?;
    let binary = builder.to_vec(rng).map_err(|e| {
        Error::new("backup_encrypt", "Could not finish the backup.").with_hint(e.to_string())
    })?;
    armor_binary(&binary)
}

/// Restore a backup that was encrypted to a public key.
pub fn restore_public_key_backup(
    armored: &str,
    secret: &pgp::composed::SignedSecretKey,
    key_password: &str,
) -> Result<String> {
    let text = normalize_armor(armored)?;
    let message = Message::from_armor(Cursor::new(text.as_bytes()))
        .map(|(m, _)| m)
        .map_err(|e| {
            Error::new("bad_backup", "That backup could not be read.").with_hint(e.to_string())
        })?;
    let mut message = message
        .decrypt(&Password::from(key_password), secret)
        .map_err(|_| {
            Error::new(
                "backup_wrong_key",
                "That backup wasn't encrypted for the key you selected.",
            )
        })?;
    if message.is_compressed() {
        message = message
            .decompress()
            .map_err(|_| Error::new("bad_backup", "That backup could not be unpacked."))?;
    }
    let bytes = message.as_data_vec().map_err(|e| {
        Error::new("bad_backup", "That backup could not be read.").with_hint(e.to_string())
    })?;
    let text = String::from_utf8(bytes)
        .map_err(|_| Error::new("bad_backup", "That backup did not contain a readable key."))?;
    parse_secret_key(text.as_bytes())?;
    Ok(text)
}

fn encrypt_with_code(plaintext: &[u8], code: &str) -> Result<String> {
    let mut rng = OsRng;
    let mut builder = MessageBuilder::from_bytes("newpgp-backup.asc", plaintext.to_vec());
    builder.compression(CompressionAlgorithm::ZLIB);
    let mut builder = builder.seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder
        .encrypt_with_password(
            // 64 MiB of memory, 3 passes: memory-hard but usable on phones.
            StringToKey::new_argon2(&mut rng, 3, 4, 16),
            &Password::from(code),
        )
        .map_err(|e| {
            Error::new("backup_encrypt", "Could not encrypt the backup.").with_hint(e.to_string())
        })?;
    let binary = builder.to_vec(rng).map_err(|e| {
        Error::new("backup_encrypt", "Could not finish the backup.").with_hint(e.to_string())
    })?;
    armor_binary(&binary)
}

fn decrypt_with_code(armored: &str, code: &str) -> Result<Vec<u8>> {
    let text = normalize_armor(armored)?;
    if !text.contains(MESSAGE_BEGIN) {
        return Err(Error::new(
            "bad_backup",
            "That doesn't look like a NewPGP backup.",
        ));
    }
    let message = Message::from_armor(Cursor::new(text.as_bytes()))
        .map(|(m, _)| m)
        .map_err(|_| Error::new("bad_backup", "That backup could not be read."))?;
    let mut message = message
        .decrypt_with_password(&Password::from(code))
        .map_err(|_| {
            Error::new(
                "wrong_setup_code",
                "That setup code is wrong, or the backup was changed in transit.",
            )
            .with_hint(
                "Double-check the code you wrote down. If it still fails, the file may be damaged.",
            )
        })?;
    if message.is_compressed() {
        message = message
            .decompress()
            .map_err(|_| Error::new("bad_backup", "That backup could not be unpacked."))?;
    }
    message.as_data_vec().map_err(|e| {
        Error::new("bad_backup", "That backup could not be read.").with_hint(e.to_string())
    })
}

fn armor_binary(binary: &[u8]) -> Result<String> {
    struct Raw(Vec<u8>);
    impl Serialize for Raw {
        fn to_writer<W: std::io::Write>(&self, w: &mut W) -> pgp::errors::Result<()> {
            w.write_all(&self.0)?;
            Ok(())
        }
        fn write_len(&self) -> usize {
            self.0.len()
        }
    }
    let mut out = Vec::new();
    armor_write(
        &Raw(binary.to_vec()),
        BlockType::Message,
        &mut out,
        None,
        true,
    )
    .map_err(|_| Error::new("armor", "Could not format the backup as text."))?;
    String::from_utf8(out).map_err(|_| Error::new("armor", "Could not format the backup as text."))
}

/// Describes a backup file for the UI.
#[derive(Debug, Clone)]
pub struct BackupInfo {
    pub kind: BackupKind,
    pub key_fingerprint: String,
    pub size: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupKind {
    SetupCode,
    PublicKey,
}

/// Inspect a backup without decrypting it, so the UI can show what it is.
pub fn inspect_backup(armored: &str) -> Result<BackupInfo> {
    let text = normalize_armor(armored)?;
    let body: String = text
        .lines()
        .filter(|line| {
            !line.starts_with("-----")
                && !line.is_empty()
                && !line.starts_with("Version:")
                && !line.starts_with('=')
        })
        .collect();
    let binary = BASE64
        .decode(body.as_bytes())
        .map_err(|_| Error::new("bad_backup", "That backup could not be read."))?;
    // OpenPGP SKESK packets have tag 3. Old-format headers are 0x8C/0x8D/0x8F,
    // new-format headers are 0xC3.
    let is_password = binary
        .first()
        .map(|b| matches!(*b, 0x8C | 0x8D | 0x8F | 0xC3))
        .unwrap_or(false);
    Ok(BackupInfo {
        kind: if is_password {
            BackupKind::SetupCode
        } else {
            BackupKind::PublicKey
        },
        key_fingerprint: String::new(),
        size: text.len(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use newpgp_core::keys::generate_identity;

    #[test]
    fn setup_code_format() {
        let code = generate_setup_code();
        assert!(validate_setup_code(&code).is_ok());
        assert_eq!(code.split('-').count(), 9);
        assert_eq!(setup_code_prefix(&code).len(), 2);
        assert!(validate_setup_code("1234-1234-1234-1234-1234-1234-1234-1234-1234").is_ok());
        assert!(validate_setup_code("1234-1234").is_err());
    }

    #[test]
    fn setup_code_backup_roundtrip() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let armor = alice.secret_armor().unwrap();
        let code = generate_setup_code();
        let backup = create_setup_code_backup(&armor, &code).unwrap();
        assert!(backup.starts_with(MESSAGE_BEGIN));
        let restored = restore_setup_code_backup(&backup, &code).unwrap();
        let key = parse_secret_key(restored.as_bytes()).unwrap();
        assert_eq!(
            raw_fingerprint(&key.primary_key),
            raw_fingerprint(&alice.secret.primary_key)
        );
        assert!(
            restore_setup_code_backup(&backup, "0000-0000-0000-0000-0000-0000-0000-0000-0000")
                .is_err()
        );
    }

    #[test]
    fn public_key_backup_roundtrip() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let bob = generate_identity("Bob", "bob@example.com", "").unwrap();
        let backup =
            create_public_key_backup(&alice.secret_armor().unwrap(), &bob.public_armor().unwrap())
                .unwrap();
        let restored = restore_public_key_backup(&backup, &bob.secret, "").unwrap();
        let key = parse_secret_key(restored.as_bytes()).unwrap();
        assert_eq!(
            raw_fingerprint(&key.primary_key),
            raw_fingerprint(&alice.secret.primary_key)
        );
    }

    #[test]
    fn inspect_detects_kind() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let code = generate_setup_code();
        let backup = create_setup_code_backup(&alice.secret_armor().unwrap(), &code).unwrap();
        assert_eq!(inspect_backup(&backup).unwrap().kind, BackupKind::SetupCode);
    }
}
