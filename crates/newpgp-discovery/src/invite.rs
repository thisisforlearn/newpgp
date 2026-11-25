// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Human-friendly invite links: the fallback for people with no published key.
//!
//! The link uses a URL *fragment* (`#...`), which browsers never send to the
//! server in an HTTP request. That keeps the email address and fingerprint out
//! of server logs. The link can also carry the full public key, so the recipient
//! does not need to hunt for it.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use newpgp_core::{Error, Result};
use serde::{Deserialize, Serialize};
use url::form_urlencoded;

pub const DEFAULT_INVITE_BASE: &str = "https://newpgp.app/add";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Invite {
    pub version: u8,
    pub email: String,
    pub fingerprint: String,
    pub public_armor: Option<String>,
}

impl Invite {
    /// Build an invite for someone else's key.
    pub fn new(email: &str, fingerprint: &str, public_armor: Option<String>) -> Result<Self> {
        let fingerprint = fingerprint.replace(' ', "").to_ascii_uppercase();
        if fingerprint.len() != 40 || !fingerprint.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(Error::new(
                "bad_fingerprint",
                "That key fingerprint doesn't look right.",
            )
            .with_hint("It should be 40 hexadecimal characters."));
        }
        if !email.contains('@') {
            return Err(Error::new(
                "bad_email",
                "That email address doesn't look right.",
            ));
        }
        Ok(Self {
            version: 1,
            email: email.trim().to_string(),
            fingerprint,
            public_armor,
        })
    }

    /// Render as a clickable link. The payload lives after `#` on purpose.
    pub fn to_url(&self, base: &str) -> Result<String> {
        if !base.starts_with("https://") {
            return Err(Error::new(
                "insecure_invite",
                "Invite links must use https so they can't be changed in transit.",
            ));
        }
        let mut pairs = vec![
            ("v", self.version.to_string()),
            ("addr", self.email.clone()),
            ("fpr", self.fingerprint.clone()),
        ];
        if let Some(armor) = &self.public_armor {
            pairs.push(("key", URL_SAFE_NO_PAD.encode(armor.as_bytes())));
        }
        let fragment = form_urlencoded::Serializer::new(String::new())
            .extend_pairs(pairs)
            .finish();
        Ok(format!("{base}#{fragment}"))
    }

    /// Parse an invite link back into structured data.
    pub fn from_url(url: &str) -> Result<Self> {
        let (base, fragment) = url.split_once('#').ok_or_else(|| {
            Error::new(
                "bad_invite",
                "That invite link is incomplete — it has no key information.",
            )
            .with_hint("Ask them to resend the whole link, starting with https://.")
        })?;
        if !base.starts_with("https://") {
            return Err(Error::new(
                "insecure_invite",
                "That invite link is not using https, so it may have been changed.",
            ));
        }

        let pairs: Vec<(String, String)> = form_urlencoded::parse(fragment.as_bytes())
            .into_owned()
            .collect();
        let get = |name: &str| -> Option<String> {
            pairs
                .iter()
                .find(|(key, _)| key == name)
                .map(|(_, value)| value.clone())
        };

        let version = get("v")
            .and_then(|v| v.parse::<u8>().ok())
            .ok_or_else(|| Error::new("bad_invite", "That invite link has an unknown format."))?;
        if version != 1 {
            return Err(Error::new(
                "bad_invite",
                "This invite link was made by a newer version of NewPGP.",
            ));
        }

        let email = get("addr")
            .ok_or_else(|| Error::new("bad_invite", "That invite link has no email address."))?;
        let fingerprint = get("fpr")
            .ok_or_else(|| Error::new("bad_invite", "That invite link has no fingerprint."))?;

        let public_armor = match get("key") {
            Some(encoded) => {
                let bytes = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| {
                    Error::new("bad_invite", "The key inside that invite link is damaged.")
                })?;
                Some(String::from_utf8(bytes).map_err(|_| {
                    Error::new("bad_invite", "The key inside that invite link is damaged.")
                })?)
            }
            None => None,
        };

        Invite::new(&email, &fingerprint, public_armor)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use newpgp_core::keys::generate_identity;

    #[test]
    fn invite_roundtrip_with_key() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let armor = alice.public_armor().unwrap();
        let invite = Invite::new(
            "alice@example.com",
            &newpgp_core::keys::raw_fingerprint(&alice.public),
            Some(armor.clone()),
        )
        .unwrap();
        let url = invite.to_url("https://newpgp.app/add").unwrap();
        assert!(!url.contains("alice%40example.com?"));
        let parsed = Invite::from_url(&url).unwrap();
        assert_eq!(parsed, invite);
        assert_eq!(parsed.public_armor.unwrap(), armor);
    }

    #[test]
    fn fragment_is_not_a_query_parameter() {
        let invite = Invite::new("alice@example.com", &"AB".repeat(20), None).unwrap();
        let url = invite.to_url("https://newpgp.app/add").unwrap();
        let after_base = url.split_once("https://newpgp.app/add").unwrap().1;
        assert!(after_base.starts_with('#'));
        assert!(!after_base.starts_with('?'));
    }

    #[test]
    fn rejects_http_and_bad_fingerprints() {
        let invite = Invite::new("a@b.com", &"AB".repeat(20), None).unwrap();
        assert!(invite.to_url("http://newpgp.app/add").is_err());
        assert!(Invite::new("a@b.com", "xyz", None).is_err());
    }

    #[test]
    fn rejects_truncated_links() {
        assert!(Invite::from_url("https://newpgp.app/add").is_err());
        assert!(Invite::from_url("http://newpgp.app/add#v=1").is_err());
    }
}
