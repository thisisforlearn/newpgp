// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! File encryption (images, video, documents — any bytes).

use std::path::{Path, PathBuf};

use pgp::composed::{SignedPublicKey, SignedSecretKey};

use crate::error::{Error, Result};
use crate::message::{decrypt_bytes, encrypt_bytes, Decrypted, EncryptionProfile, SignatureStatus};

/// Encrypt a file. Works for text, images, video, archives — anything.
///
/// Returns the number of plaintext bytes processed.
pub fn encrypt_file(
    input: &Path,
    output: &Path,
    recipients: &[SignedPublicKey],
    signer: Option<(&SignedSecretKey, &str)>,
    profile: EncryptionProfile,
) -> Result<u64> {
    let data = std::fs::read(input).map_err(|e| {
        Error::new(
            "read_failed",
            format!("Could not read '{}'.", input.display()),
        )
        .with_hint(e.to_string())
    })?;

    let name = input
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string();

    let encrypted = encrypt_bytes(&data, recipients, signer, &name, profile)?;

    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent).map_err(|e| {
            Error::new("write_failed", "Could not create the output folder.")
                .with_hint(e.to_string())
        })?;
    }
    std::fs::write(output, &encrypted.binary).map_err(|e| {
        Error::new(
            "write_failed",
            format!("Could not write '{}'.", output.display()),
        )
        .with_hint(e.to_string())
    })?;

    Ok(data.len() as u64)
}

/// Decrypt a file to `output`, or to a sensible sibling path when `None`.
///
/// The returned path is where the plaintext was written.
pub fn decrypt_file(
    input: &Path,
    output: Option<&Path>,
    secret: &SignedSecretKey,
    key_password: &str,
    known_keys: &[SignedPublicKey],
) -> Result<(PathBuf, Decrypted)> {
    let data = std::fs::read(input).map_err(|e| {
        Error::new(
            "read_failed",
            format!("Could not read '{}'.", input.display()),
        )
        .with_hint(e.to_string())
    })?;

    let decrypted = decrypt_bytes(&data, secret, key_password, known_keys)?;
    let target = match output {
        Some(path) => path.to_path_buf(),
        None => default_decrypted_path(input),
    };

    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(|e| {
            Error::new("write_failed", "Could not create the output folder.")
                .with_hint(e.to_string())
        })?;
    }
    std::fs::write(&target, &decrypted.data).map_err(|e| {
        Error::new(
            "write_failed",
            format!("Could not write '{}'.", target.display()),
        )
        .with_hint(e.to_string())
    })?;

    Ok((target, decrypted))
}

fn default_decrypted_path(input: &Path) -> PathBuf {
    let name = input
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("encrypted.pgp");
    let base = name
        .strip_suffix(".pgp")
        .or_else(|| name.strip_suffix(".gpg"))
        .or_else(|| name.strip_suffix(".asc"))
        .unwrap_or(name);

    let mut candidate = input.with_file_name(base);
    if candidate == input || candidate.exists() {
        let stem = Path::new(base)
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("decrypted");
        let ext = Path::new(base)
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| format!(".{e}"))
            .unwrap_or_default();
        candidate = input.with_file_name(format!("{stem}_decrypted{ext}"));
    }
    candidate
}

/// Format a signature result for display in a file manager context menu.
pub fn signature_label(status: &SignatureStatus) -> &'static str {
    status.label()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::generate_identity;
    #[test]
    fn file_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let input = dir.path().join("photo.png");
        let original = b"\x89PNG\r\n\x1a\n fake image bytes";
        std::fs::write(&input, original).unwrap();

        let encrypted_path = dir.path().join("photo.png.pgp");
        let written = encrypt_file(
            &input,
            &encrypted_path,
            std::slice::from_ref(&alice.public),
            None,
            Default::default(),
        )
        .unwrap();
        assert_eq!(written, original.len() as u64);

        let (out, _) = decrypt_file(&encrypted_path, None, &alice.secret, "", &[]).unwrap();
        assert_eq!(std::fs::read(out).unwrap(), original);
    }

    #[test]
    fn default_output_strips_pgp_suffix() {
        let p = default_decrypted_path(Path::new("C:/tmp/movie.mp4.pgp"));
        assert_eq!(p, Path::new("C:/tmp/movie.mp4"));
    }
}
