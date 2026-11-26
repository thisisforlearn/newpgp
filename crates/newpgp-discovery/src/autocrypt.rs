// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Autocrypt Level 1 headers: in-band key distribution without key servers.
//!
//! The `Autocrypt` header carries a minimized OpenPGP public key in every
//! outgoing message. Peers learn keys just by receiving email. See
//! https://autocrypt.org/level1.html

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use newpgp_core::keys::parse_public_key;
use newpgp_core::{Error, Result};
use pgp::composed::SignedPublicKey;
use pgp::ser::Serialize;

/// A parsed Autocrypt header.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AutocryptHeader {
    pub addr: String,
    pub prefer_encrypt_mutual: bool,
    pub keydata: Vec<u8>,
}

/// Build an `Autocrypt:` header line for an outgoing message.
///
/// Autocrypt Level 1 calls for a *minimized* certificate: one signing-capable
/// primary key, one User ID with one self-signature, one encryption subkey with
/// one binding signature — five packets. rPGP exposes all of those fields, so
/// imported certificates are minimized here too: we keep the first User ID and
/// the first subkey and discard additional signatures, user attributes and
/// revocation/direct signatures (the minimized form has no place for them). A
/// certificate with no encryption subkey yields only four packets; we do not
/// fabricate one. Keys from `newpgp_core::keys::generate_identity` already have
/// the five-packet shape.
pub fn build_header(
    cert: &SignedPublicKey,
    addr: &str,
    prefer_encrypt_mutual: bool,
) -> Result<String> {
    if !addr.contains('@') {
        return Err(Error::new(
            "bad_email",
            "That email address doesn't look right.",
        ));
    }

    let mut minimized = cert.clone();
    minimized.details.revocation_signatures.clear();
    minimized.details.direct_signatures.clear();
    minimized.details.user_attributes.clear();
    if let Some(user) = minimized.details.users.first_mut() {
        user.signatures.truncate(1);
    }
    minimized.details.users.truncate(1);
    if let Some(subkey) = minimized.public_subkeys.first_mut() {
        subkey.signatures.truncate(1);
    }
    minimized.public_subkeys.truncate(1);

    let binary = minimized.to_bytes().map_err(|e| {
        Error::new("key_export", "Could not prepare the key for the header.")
            .with_hint(e.to_string())
    })?;

    let mut header = format!("Autocrypt: addr={addr}; ");
    if prefer_encrypt_mutual {
        header.push_str("prefer-encrypt=mutual; ");
    }
    header.push_str("keydata=");
    header.push_str(&BASE64.encode(binary));
    // Autocrypt Level 1 caps the complete header at 10 KiB after base64,
    // including the attribute names and the address.
    if header.len() > 10 * 1024 {
        return Err(Error::new(
            "autocrypt_too_big",
            "This key is too large for an Autocrypt header.",
        )
        .with_hint("Generate a fresh Ed25519/Curve25519 key instead of a large RSA key."));
    }
    Ok(header)
}

/// Parse a single `Autocrypt:` header value (without the `Autocrypt:` prefix).
///
/// `from_addr` must be the address in the message's `From:` header. If it does
/// not match the header's `addr` attribute, the whole header is invalid.
pub fn parse_header(header: &str, from_addr: &str) -> Result<AutocryptHeader> {
    let header = header
        .trim()
        .strip_prefix("Autocrypt:")
        .unwrap_or(header)
        .trim();

    let mut addr: Option<String> = None;
    let mut prefer = false;
    let mut keydata: Option<String> = None;

    for part in header.split(';') {
        let part = part.trim();
        let Some((name, value)) = part.split_once('=') else {
            continue;
        };
        match name.trim().to_ascii_lowercase().as_str() {
            "addr" => addr = Some(value.trim().to_string()),
            "prefer-encrypt" => {
                // Level 1 defines `mutual` and `nopreference`; unknown values
                // must be treated as nopreference rather than rejected.
                prefer = value.trim().eq_ignore_ascii_case("mutual");
            }
            "keydata" => keydata = Some(value.trim().to_string()),
            other if other.starts_with('_') => {
                // Non-critical extension attributes can be ignored.
            }
            _ => {
                return Err(Error::new(
                    "autocrypt_invalid",
                    "That email's Autocrypt header has an unsupported part, so it was ignored.",
                ));
            }
        }
    }

    let addr = addr.ok_or_else(|| {
        Error::new(
            "autocrypt_invalid",
            "That email's Autocrypt header has no address.",
        )
    })?;
    if !addr.eq_ignore_ascii_case(from_addr) {
        return Err(Error::new(
            "autocrypt_invalid",
            "That email's key header doesn't match the sender, so it was ignored.",
        ));
    }
    let keydata = keydata.ok_or_else(|| {
        Error::new(
            "autocrypt_invalid",
            "That email's Autocrypt header has no key.",
        )
    })?;
    if keydata.len() > 14_000 {
        return Err(Error::new(
            "autocrypt_too_big",
            "That email's key header is unreasonably large, so it was ignored.",
        ));
    }
    let binary = BASE64.decode(keydata).map_err(|_| {
        Error::new(
            "autocrypt_invalid",
            "That email's key header is not valid data.",
        )
    })?;
    // Validate that it really is an OpenPGP certificate before we hand it on.
    parse_public_key(&binary)?;

    Ok(AutocryptHeader {
        addr,
        prefer_encrypt_mutual: prefer,
        keydata: binary,
    })
}

/// `Autocrypt-Draft-State` header for encrypted drafts stored on the server.
pub fn draft_state_header(encrypt: bool, is_reply_to_encrypted: bool, by_choice: bool) -> String {
    let mut value = format!(
        "Autocrypt-Draft-State: encrypt={};",
        if encrypt { "yes" } else { "no" }
    );
    if is_reply_to_encrypted {
        value.push_str(" _is-reply-to-encrypted=yes;");
    }
    if by_choice {
        value.push_str(" _by-choice=yes;");
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;
    use newpgp_core::keys::generate_identity;

    #[test]
    fn header_roundtrip() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let header = build_header(&alice.public, "alice@example.com", true).unwrap();
        assert!(header.contains("prefer-encrypt=mutual"));
        let parsed = parse_header(&header, "alice@example.com").unwrap();
        assert!(parsed.prefer_encrypt_mutual);
        let cert = parse_public_key(&parsed.keydata).unwrap();
        assert_eq!(
            newpgp_core::keys::raw_fingerprint(&cert),
            newpgp_core::keys::raw_fingerprint(&alice.public)
        );
    }

    #[test]
    fn nopreference_is_accepted_as_no_preference() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let mut header = build_header(&alice.public, "alice@example.com", false).unwrap();
        header.insert_str(
            header.find("keydata=").unwrap(),
            "prefer-encrypt=nopreference; ",
        );
        let parsed = parse_header(&header, "alice@example.com").unwrap();
        assert!(!parsed.prefer_encrypt_mutual);

        let mut header = build_header(&alice.public, "alice@example.com", true).unwrap();
        header.insert_str(header.find("keydata=").unwrap(), "prefer-encrypt=UNKNOWN; ");
        let parsed = parse_header(&header, "alice@example.com").unwrap();
        assert!(!parsed.prefer_encrypt_mutual);
    }

    #[test]
    fn header_is_minimized_to_one_user_and_one_subkey() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let extra = generate_identity("Other", "other@example.com", "").unwrap();
        let mut cert = alice.public.clone();
        cert.details
            .users
            .push(extra.public.details.users[0].clone());
        let header = build_header(&cert, "alice@example.com", false).unwrap();
        let parsed = parse_header(&header, "alice@example.com").unwrap();
        let minimized = parse_public_key(&parsed.keydata).unwrap();
        assert_eq!(minimized.details.users.len(), 1);
        assert_eq!(minimized.public_subkeys.len(), 1);
    }

    #[test]
    fn header_longer_than_ten_kib_is_rejected() {
        let alice = generate_identity(&"A".repeat(9_000), "alice@example.com", "").unwrap();
        let err = build_header(&alice.public, "alice@example.com", false).unwrap_err();
        assert_eq!(err.code, "autocrypt_too_big");
    }

    #[test]
    fn header_from_wrong_sender_is_rejected() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let header = build_header(&alice.public, "alice@example.com", false).unwrap();
        assert!(parse_header(&header, "eve@example.com").is_err());
    }

    #[test]
    fn unknown_critical_attribute_is_rejected() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let mut header = build_header(&alice.public, "alice@example.com", false).unwrap();
        header.push_str(" weird=thing;");
        assert!(parse_header(&header, "alice@example.com").is_err());
    }

    #[test]
    fn noncritical_attribute_is_ignored() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let mut header = build_header(&alice.public, "alice@example.com", false).unwrap();
        header.insert_str(header.find("keydata=").unwrap(), "_future=yes; ");
        assert!(parse_header(&header, "alice@example.com").is_ok());
    }

    #[test]
    fn draft_state_header_shape() {
        assert_eq!(
            draft_state_header(true, false, true),
            "Autocrypt-Draft-State: encrypt=yes; _by-choice=yes;"
        );
    }
}
