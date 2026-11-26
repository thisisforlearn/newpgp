// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Local contact keys with trust-on-first-use (TOFU).
//!
//! Instead of the Web of Trust, NewPGP remembers the key we first saw for an
//! address and makes a loud, plain-language warning if it ever changes. Users
//! confirm a short safety number out of band when they care.

use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use newpgp_core::keys::{raw_fingerprint, safety_number};
use newpgp_core::{Error, Result};
use pgp::composed::SignedPublicKey;
use serde::{Deserialize, Serialize};
use tracing::warn;

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// Where a key came from. Shown in the UI so the user knows how it was found.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum KeySource {
    WebKeyDirectory,
    KeysOpenpgpOrg,
    Autocrypt,
    InviteLink,
    #[default]
    Manual,
}

impl KeySource {
    pub fn label(&self) -> &'static str {
        match self {
            Self::WebKeyDirectory => "found automatically on the email domain",
            Self::KeysOpenpgpOrg => "found in a verified public directory",
            Self::Autocrypt => "received in an email from them",
            Self::InviteLink => "from an invite link they shared",
            Self::Manual => "added by you",
        }
    }
}

/// How much confidence we have in a key.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum Verification {
    #[default]
    TrustOnFirstUse,
    VerifiedBySafetyNumber {
        code: String,
        verified_at: u64,
    },
}

impl Verification {
    pub fn label(&self) -> &'static str {
        match self {
            Self::TrustOnFirstUse => "First time seen — compare the safety number to be sure",
            Self::VerifiedBySafetyNumber { .. } => "You verified this key with a safety number",
        }
    }
}

/// A contact and the key we believe belongs to them.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Contact {
    pub email: String,
    pub fingerprint: String,
    pub public_armor: String,
    pub source: KeySource,
    pub first_seen: u64,
    pub last_seen: u64,
    pub verification: Verification,
}

impl Contact {
    /// Parse the stored armor into a usable OpenPGP certificate.
    pub fn public_key(&self) -> Result<SignedPublicKey> {
        newpgp_core::keys::parse_public_key(self.public_armor.as_bytes())
    }

    /// The long fingerprint, spaced for reading aloud.
    pub fn pretty_fingerprint(&self) -> String {
        self.fingerprint
            .as_bytes()
            .chunks(4)
            .map(|c| std::str::from_utf8(c).unwrap_or(""))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

/// What happened when a key was seen again.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TofuOutcome {
    /// We had never seen this address before.
    NewContact(Contact),
    /// Same key as before; nothing to do.
    Unchanged(Contact),
    /// Different key than before. This may be legitimate rotation or an attack.
    KeyChanged { previous: Contact, current: Contact },
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Store {
    contacts: BTreeMap<String, Contact>,
    /// A key that replaced a trusted key but has not been confirmed yet.
    /// Kept separate from `contacts` so an unattended save can never promote
    /// a possible attacker key to the trusted one.
    #[serde(default)]
    pending_changes: BTreeMap<String, Contact>,
}

/// A small JSON-backed keyring. Safe to keep in the user's app-data folder.
pub struct KeyRing {
    path: PathBuf,
    store: Store,
}

impl KeyRing {
    /// Open a keyring, creating it in memory if the file does not exist yet.
    pub fn open(path: &Path) -> Result<Self> {
        let store = if path.exists() {
            let bytes = std::fs::read(path).map_err(|e| {
                Error::new("keyring_read", "Could not open your contact keyring.")
                    .with_hint(e.to_string())
            })?;
            serde_json::from_slice(&bytes).map_err(|e| {
                Error::new("keyring_corrupt", "Your contact keyring could not be read.")
                    .with_hint("The original file was left untouched. Restore a backup or rebuild it from your contacts.")
                    .with_hint(e.to_string())
            })?
        } else {
            Store::default()
        };
        Ok(Self {
            path: path.to_path_buf(),
            store,
        })
    }

    /// Persist atomically: write a uniquely named temporary file, then replace
    /// the original with it. `NamedTempFile::persist` uses an atomic replace on
    /// Windows, so the keyring is never briefly missing during a save.
    pub fn save(&self) -> Result<()> {
        let parent = self
            .path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        std::fs::create_dir_all(parent).map_err(|e| {
            Error::new("keyring_dir", "Could not create the keyring folder.")
                .with_hint(e.to_string())
        })?;
        let bytes = serde_json::to_vec_pretty(&self.store)
            .map_err(|_| Error::new("keyring_write", "Could not serialize the keyring."))?;
        let mut tmp = tempfile::NamedTempFile::new_in(parent).map_err(|e| {
            Error::new("keyring_write", "Could not save your contact keyring.")
                .with_hint(e.to_string())
        })?;
        tmp.write_all(&bytes).map_err(|e| {
            Error::new("keyring_write", "Could not save your contact keyring.")
                .with_hint(e.to_string())
        })?;
        tmp.persist(&self.path).map_err(|e| {
            Error::new("keyring_write", "Could not finalize the keyring save.")
                .with_hint(e.to_string())
        })?;
        Ok(())
    }

    /// Normalize an address before using it as a key. This prevents
    /// "Alice@Example.com" and "alice@example.com" from becoming two contacts.
    pub fn canonical_email(email: &str) -> String {
        let email = email.trim();
        email.to_ascii_lowercase()
    }

    /// Learn a key for an address, detecting changes.
    pub fn learn(
        &mut self,
        email: &str,
        cert: &SignedPublicKey,
        source: KeySource,
    ) -> Result<TofuOutcome> {
        let email = Self::canonical_email(email);
        let fingerprint = raw_fingerprint(cert);
        let public_armor = cert.to_armored_string(Default::default()).map_err(|e| {
            Error::new("key_export", "Could not store that key.").with_hint(e.to_string())
        })?;

        if let Some(previous) = self.store.contacts.get(&email).cloned() {
            if previous.fingerprint == fingerprint {
                let mut updated = previous.clone();
                updated.last_seen = now();
                self.store.contacts.insert(email, updated.clone());
                Ok(TofuOutcome::Unchanged(updated))
            } else {
                warn!(%email, "contact key changed");
                let current = Contact {
                    email: email.clone(),
                    fingerprint,
                    public_armor,
                    source,
                    first_seen: now(),
                    last_seen: now(),
                    verification: Verification::TrustOnFirstUse,
                };
                // Verification never carries over to a different key, and the
                // replacement is NOT trusted until the user confirms a fresh
                // safety number. Keep the old key as the active contact and
                // park the new one in `pending_changes`.
                self.store.pending_changes.insert(email, current.clone());
                Ok(TofuOutcome::KeyChanged { previous, current })
            }
        } else {
            let contact = Contact {
                email,
                fingerprint,
                public_armor,
                source,
                first_seen: now(),
                last_seen: now(),
                verification: Verification::TrustOnFirstUse,
            };
            self.store
                .contacts
                .insert(contact.email.clone(), contact.clone());
            Ok(TofuOutcome::NewContact(contact))
        }
    }

    /// Confirm a key by comparing an out-of-band safety number.
    pub fn verify(&mut self, email: &str, code: &str) -> Result<()> {
        let email = Self::canonical_email(email);
        let expected = {
            let contact = self.store.contacts.get(&email).ok_or_else(|| {
                Error::new(
                    "unknown_contact",
                    "I don't have a key for that address yet.",
                )
            })?;
            self.contact_safety_number(contact)?
        };
        let contact = self.store.contacts.get_mut(&email).ok_or_else(|| {
            Error::new(
                "unknown_contact",
                "I don't have a key for that address yet.",
            )
        })?;
        if expected == code.trim() {
            contact.verification = Verification::VerifiedBySafetyNumber {
                code: expected,
                verified_at: now(),
            };
            Ok(())
        } else {
            Err(Error::new(
                "safety_mismatch",
                "Those safety numbers don't match — stop and check with them another way.",
            )
            .with_hint("Call them on a number you already trust, or compare in person."))
        }
    }

    fn contact_safety_number(&self, contact: &Contact) -> Result<String> {
        let my_key = self
            .store
            .contacts
            .values()
            .find(|c| c.email.is_empty())
            .and_then(|c| c.public_key().ok());
        match my_key {
            Some(key) => Ok(safety_number(&key, &contact.public_key()?)),
            None => Ok(short_code(&contact.fingerprint)),
        }
    }

    /// Safety number to compare with a contact, based on our identity if set.
    pub fn safety_number_for(&self, email: &str) -> Result<String> {
        let email = Self::canonical_email(email);
        let contact = self.store.contacts.get(&email).ok_or_else(|| {
            Error::new(
                "unknown_contact",
                "I don't have a key for that address yet.",
            )
        })?;
        self.contact_safety_number(contact)
    }

    /// Remember our own identity so safety numbers are two-sided.
    pub fn set_own_identity(&mut self, cert: &SignedPublicKey) -> Result<()> {
        let fingerprint = raw_fingerprint(cert);
        let public_armor = cert.to_armored_string(Default::default()).map_err(|e| {
            Error::new("key_export", "Could not store your key.").with_hint(e.to_string())
        })?;
        // Safety numbers are two-sided: rotating our own identity changes every
        // one of them. Any safety number verified against the old identity is
        // no longer meaningful and must be re-compared out of band.
        let rotated = self
            .own_identity()
            .is_some_and(|old| old.fingerprint != fingerprint);
        if rotated {
            for contact in self.store.contacts.values_mut() {
                contact.verification = Verification::TrustOnFirstUse;
            }
        }
        self.store.contacts.insert(
            String::new(),
            Contact {
                email: String::new(),
                fingerprint,
                public_armor,
                source: KeySource::Manual,
                first_seen: now(),
                last_seen: now(),
                verification: Verification::TrustOnFirstUse,
            },
        );
        Ok(())
    }

    /// Our own identity, stored under the empty canonical address.
    pub fn own_identity(&self) -> Option<&Contact> {
        self.store.contacts.get("")
    }

    /// An unconfirmed replacement key, if the user has been warned about one.
    pub fn pending_change(&self, email: &str) -> Option<&Contact> {
        self.store
            .pending_changes
            .get(&Self::canonical_email(email))
    }

    /// Accept an unconfirmed key change, making it the trusted contact.
    pub fn confirm_change(&mut self, email: &str) -> Result<()> {
        let email = Self::canonical_email(email);
        let current = self.store.pending_changes.remove(&email).ok_or_else(|| {
            Error::new(
                "no_pending_change",
                "There is no unconfirmed key change for that address.",
            )
        })?;
        self.store.contacts.insert(email, current);
        Ok(())
    }

    /// Reject an unconfirmed key change, keeping the previously trusted key.
    pub fn reject_change(&mut self, email: &str) -> Result<()> {
        let email = Self::canonical_email(email);
        if self.store.pending_changes.remove(&email).is_none() {
            return Err(Error::new(
                "no_pending_change",
                "There is no unconfirmed key change for that address.",
            ));
        }
        Ok(())
    }

    pub fn get(&self, email: &str) -> Option<&Contact> {
        self.store.contacts.get(&Self::canonical_email(email))
    }

    pub fn remove(&mut self, email: &str) -> Option<Contact> {
        self.store.contacts.remove(&Self::canonical_email(email))
    }

    pub fn contacts(&self) -> impl Iterator<Item = &Contact> {
        self.store.contacts.values().filter(|c| !c.email.is_empty())
    }

    pub fn len(&self) -> usize {
        self.store
            .contacts
            .values()
            .filter(|c| !c.email.is_empty())
            .count()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// A short, readable code derived from a fingerprint, used when no own
/// identity is configured yet. Clearly documented as weaker than a full
/// two-sided safety number.
pub fn short_code(fingerprint_hex: &str) -> String {
    let pretty = format_fingerprint_from_hex(fingerprint_hex);
    pretty
        .split_whitespace()
        .take(6)
        .collect::<Vec<_>>()
        .join(" ")
}

fn format_fingerprint_from_hex(hex: &str) -> String {
    hex.to_ascii_uppercase()
        .as_bytes()
        .chunks(4)
        .map(|c| std::str::from_utf8(c).unwrap_or(""))
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use newpgp_core::keys::generate_identity;
    use tempfile::tempdir;

    fn identity(email: &str) -> newpgp_core::Identity {
        generate_identity("Test User", email, "").unwrap()
    }

    #[test]
    fn learns_a_key_and_detects_persistence() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("contacts.json");
        let alice = identity("alice@example.com");

        {
            let mut ring = KeyRing::open(&path).unwrap();
            let outcome = ring
                .learn("Alice@Example.com", &alice.public, KeySource::Autocrypt)
                .unwrap();
            assert!(matches!(outcome, TofuOutcome::NewContact(_)));
            ring.save().unwrap();
        }
        {
            let ring = KeyRing::open(&path).unwrap();
            let contact = ring.get("alice@example.com").unwrap();
            assert_eq!(
                contact.fingerprint,
                raw_fingerprint(&alice.public.primary_key)
            );
            assert_eq!(contact.source, KeySource::Autocrypt);
            assert_eq!(ring.len(), 1);
        }
    }

    #[test]
    fn warns_when_a_key_changes() {
        let dir = tempdir().unwrap();
        let mut ring = KeyRing::open(&dir.path().join("k.json")).unwrap();
        let first = identity("bob@example.com");
        let second = identity("bob@example.com");

        ring.learn("bob@example.com", &first.public, KeySource::WebKeyDirectory)
            .unwrap();
        let outcome = ring
            .learn("bob@example.com", &second.public, KeySource::InviteLink)
            .unwrap();
        match outcome {
            TofuOutcome::KeyChanged { previous, current } => {
                assert_eq!(
                    previous.fingerprint,
                    raw_fingerprint(&first.public.primary_key)
                );
                assert_eq!(
                    current.fingerprint,
                    raw_fingerprint(&second.public.primary_key)
                );
            }
            other => panic!("expected KeyChanged, got {other:?}"),
        }
        // The new key must not become trusted just because it was seen.
        assert_eq!(
            ring.get("bob@example.com").unwrap().fingerprint,
            raw_fingerprint(&first.public.primary_key)
        );
        let pending = ring.pending_change("BOB@EXAMPLE.COM").unwrap();
        assert_eq!(
            pending.fingerprint,
            raw_fingerprint(&second.public.primary_key)
        );
        assert_eq!(pending.email, "bob@example.com");
    }

    #[test]
    fn confirming_a_change_promotes_the_pending_key() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("k.json");
        let first = identity("bob@example.com");
        let second = identity("bob@example.com");
        {
            let mut ring = KeyRing::open(&path).unwrap();
            ring.learn("bob@example.com", &first.public, KeySource::Autocrypt)
                .unwrap();
            ring.learn("bob@example.com", &second.public, KeySource::Autocrypt)
                .unwrap();
            assert_eq!(
                ring.get("bob@example.com").unwrap().fingerprint,
                raw_fingerprint(&first.public.primary_key)
            );
            ring.confirm_change("bob@example.com").unwrap();
            ring.save().unwrap();
        }
        let mut ring = KeyRing::open(&path).unwrap();
        assert_eq!(
            ring.get("bob@example.com").unwrap().fingerprint,
            raw_fingerprint(&second.public.primary_key)
        );
        assert!(ring.pending_change("bob@example.com").is_none());
        assert!(ring.confirm_change("bob@example.com").is_err());
    }

    #[test]
    fn rejecting_a_change_keeps_the_old_key() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("k.json");
        let first = identity("bob@example.com");
        let second = identity("bob@example.com");
        {
            let mut ring = KeyRing::open(&path).unwrap();
            ring.learn("bob@example.com", &first.public, KeySource::Autocrypt)
                .unwrap();
            ring.learn("bob@example.com", &second.public, KeySource::Autocrypt)
                .unwrap();
            ring.reject_change("bob@example.com").unwrap();
            assert!(ring.pending_change("bob@example.com").is_none());
            ring.save().unwrap();
        }
        let mut ring = KeyRing::open(&path).unwrap();
        assert_eq!(
            ring.get("bob@example.com").unwrap().fingerprint,
            raw_fingerprint(&first.public.primary_key)
        );
        assert!(ring.reject_change("bob@example.com").is_err());
    }

    #[test]
    fn rotating_our_identity_resets_every_verification() {
        let dir = tempdir().unwrap();
        let mut ring = KeyRing::open(&dir.path().join("k.json")).unwrap();
        let old_me = identity("me@example.com");
        let new_me = identity("me@example.com");
        let alice = identity("alice@example.com");
        let bob = identity("bob@example.com");
        ring.set_own_identity(&old_me.public).unwrap();
        ring.learn("alice@example.com", &alice.public, KeySource::Manual)
            .unwrap();
        ring.learn("bob@example.com", &bob.public, KeySource::Manual)
            .unwrap();
        for email in ["alice@example.com", "bob@example.com"] {
            let code = ring.safety_number_for(email).unwrap();
            ring.verify(email, &code).unwrap();
            assert!(matches!(
                ring.get(email).unwrap().verification,
                Verification::VerifiedBySafetyNumber { .. }
            ));
        }

        ring.set_own_identity(&new_me.public).unwrap();
        assert_eq!(
            ring.own_identity().unwrap().fingerprint,
            raw_fingerprint(&new_me.public.primary_key)
        );
        for email in ["alice@example.com", "bob@example.com"] {
            assert_eq!(
                ring.get(email).unwrap().verification,
                Verification::TrustOnFirstUse
            );
        }
    }

    #[test]
    fn same_key_is_unchanged() {
        let dir = tempdir().unwrap();
        let mut ring = KeyRing::open(&dir.path().join("k.json")).unwrap();
        let alice = identity("alice@example.com");
        ring.learn("alice@example.com", &alice.public, KeySource::Manual)
            .unwrap();
        let outcome = ring
            .learn("alice@example.com", &alice.public, KeySource::Manual)
            .unwrap();
        assert!(matches!(outcome, TofuOutcome::Unchanged(_)));
    }

    #[test]
    fn verification_accepts_only_the_matching_code() {
        let dir = tempdir().unwrap();
        let mut ring = KeyRing::open(&dir.path().join("k.json")).unwrap();
        let me = identity("me@example.com");
        let alice = identity("alice@example.com");
        ring.set_own_identity(&me.public).unwrap();
        ring.learn("alice@example.com", &alice.public, KeySource::InviteLink)
            .unwrap();
        let code = ring.safety_number_for("alice@example.com").unwrap();
        assert!(ring.verify("alice@example.com", &code).is_ok());
        match &ring.get("alice@example.com").unwrap().verification {
            Verification::VerifiedBySafetyNumber { code: stored, .. } => assert_eq!(stored, &code),
            other => panic!("expected verified key, got {other:?}"),
        }
        assert!(ring.verify("alice@example.com", "0000 0000").is_err());
    }
}
