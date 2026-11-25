// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! OpenPGP Web Key Directory (WKD) URL construction.
//!
//! See draft-koch-openpgp-webkey-service-22 section 3.1. The SHA-1 here is
//! only a filename mapping, explicitly NOT a security feature.

use newpgp_core::{Error, Result};
use sha1::{Digest, Sha1};
use url::form_urlencoded;

const ZBASE32_ALPHABET: &[u8; 32] = b"ybndrfg8ejkmcpqxot1uwisza345h769";

/// Z-Base-32 encoding (RFC 6189 section 5.1.6), MSB first.
pub fn zbase32(data: &[u8]) -> String {
    let mut out = String::with_capacity((data.len() * 8).div_ceil(5));
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    for &byte in data {
        acc = (acc << 8) | byte as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            let index = ((acc >> bits) & 0b1_1111) as usize;
            out.push(ZBASE32_ALPHABET[index] as char);
        }
    }
    if bits > 0 {
        let index = ((acc << (5 - bits)) & 0b1_1111) as usize;
        out.push(ZBASE32_ALPHABET[index] as char);
    }
    out
}

/// The 32-character WKD hash of the local part of an address.
pub fn local_hash(local: &str) -> String {
    // Only ASCII uppercase is folded, exactly as the draft specifies.
    let mapped = local.to_ascii_lowercase();
    let digest = Sha1::digest(mapped.as_bytes());
    zbase32(&digest)
}

/// WKD's preferred "advanced method": a dedicated `openpgpkey` subdomain.
pub fn advanced_url(local: &str, domain: &str) -> Result<String> {
    check_parts(local, domain)?;
    let hash = local_hash(local);
    let encoded = form_urlencoded::byte_serialize(local.as_bytes()).collect::<String>();
    Ok(format!(
        "https://openpgpkey.{domain}/.well-known/openpgpkey/{domain}/hu/{hash}?l={encoded}"
    ))
}

/// WKD fallback when the `openpgpkey` subdomain does not exist.
pub fn direct_url(local: &str, domain: &str) -> Result<String> {
    check_parts(local, domain)?;
    let hash = local_hash(local);
    let encoded = form_urlencoded::byte_serialize(local.as_bytes()).collect::<String>();
    Ok(format!(
        "https://{domain}/.well-known/openpgpkey/hu/{hash}?l={encoded}"
    ))
}

fn check_parts(local: &str, domain: &str) -> Result<()> {
    if local.is_empty() || domain.is_empty() {
        return Err(Error::new(
            "bad_email",
            "That email address doesn't look right.",
        ));
    }
    if domain.contains('/') || domain.contains('@') {
        return Err(Error::new(
            "bad_email",
            "That email address doesn't look right.",
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_the_wkd_spec_example() {
        // draft-koch-openpgp-webkey-service-22, section 3.1
        assert_eq!(local_hash("Joe.Doe"), "iy9q119eutrkn8s1mk4r39qejnbu3n5q");
    }

    #[test]
    fn advanced_url_shape() {
        let url = advanced_url("Joe.Doe", "Example.ORG").unwrap();
        assert_eq!(
            url,
            "https://openpgpkey.Example.ORG/.well-known/openpgpkey/Example.ORG/hu/iy9q119eutrkn8s1mk4r39qejnbu3n5q?l=Joe.Doe"
        );
    }

    #[test]
    fn direct_url_shape() {
        let url = direct_url("alice", "example.org").unwrap();
        assert!(url.starts_with("https://example.org/.well-known/openpgpkey/hu/"));
        assert!(url.ends_with("?l=alice"));
    }

    #[test]
    fn encodes_special_characters_in_local_part() {
        let url = direct_url("a+b", "example.org").unwrap();
        assert!(url.ends_with("?l=a%2Bb"));
    }

    #[test]
    fn zbase32_of_empty_is_empty() {
        assert_eq!(zbase32(&[]), "");
    }
}
