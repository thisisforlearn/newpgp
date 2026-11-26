// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Zero-hassle public key discovery.
//!
//! Order of attempts: WKD advanced, WKD direct, keys.openpgp.org. Autocrypt
//! headers and invite links are handled as separate, offline sources.
//!
//! References:
//! - draft-koch-openpgp-webkey-service-22
//! - https://keys.openpgp.org/
//! - Autocrypt Level 1

use newpgp_core::keys::{parse_public_key, raw_fingerprint};
use newpgp_core::{Error, Result};
use newpgp_keyring::KeySource;
use pgp::composed::SignedPublicKey;
use pgp::ser::Serialize as PgpSerialize;
use serde::{Deserialize, Serialize};
use url::form_urlencoded;

pub mod autocrypt;
pub mod invite;
pub mod wkd;

/// Something that can fetch a URL. Injectable so tests never need the network.
#[async_trait::async_trait]
pub trait KeyFetcher: Send + Sync {
    async fn fetch(&self, url: &str) -> Result<Vec<u8>>;
}

/// Real HTTPS fetcher, using rustls (no OpenSSL required on any platform).
pub struct HttpFetcher {
    client: reqwest::Client,
}

impl Default for HttpFetcher {
    fn default() -> Self {
        Self::new()
    }
}

impl HttpFetcher {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .user_agent(concat!("NewPGP/", env!("CARGO_PKG_VERSION")))
                .timeout(std::time::Duration::from_secs(15))
                .build()
                .unwrap_or_else(|_| reqwest::Client::new()),
        }
    }
}

#[async_trait::async_trait]
impl KeyFetcher for HttpFetcher {
    async fn fetch(&self, url: &str) -> Result<Vec<u8>> {
        let response = self.client.get(url).send().await.map_err(|e| {
            Error::new("network", "I couldn't reach the key directory.")
                .with_hint(format!("Check your internet connection. ({e})"))
        })?;

        if let Some(err) = status_error(response.status().as_u16()) {
            return Err(err);
        }

        response.bytes().await.map(|b| b.to_vec()).map_err(|e| {
            Error::new("network", "The key download was interrupted.").with_hint(e.to_string())
        })
    }
}

/// Translate an HTTP status into a typed error. `wkd_auth` and `network` are
/// deliberately distinct from `not_found`: a directory that demands
/// authentication or cannot answer is not the same as "no key published", and
/// must not trigger a fallback search.
fn status_error(status: u16) -> Option<Error> {
    if (200..300).contains(&status) {
        return None;
    }
    if status == 401 {
        return Some(
            Error::new("wkd_auth", "The key directory asked for authentication.")
                .with_hint("Ask your mail administrator why the WKD endpoint is protected."),
        );
    }
    if status == 404 || status == 410 {
        return Some(
            Error::new("not_found", "No published key was found for that address.").with_hint(
                "They can send you an invite link, or you can use an Autocrypt-enabled mail app.",
            ),
        );
    }
    Some(
        Error::new(
            "network",
            "The key directory could not answer that request.",
        )
        .with_hint(format!("The server returned HTTP {status}.")),
    )
}

/// A key we found, and how we found it.
#[derive(Debug, Clone)]
pub struct Discovery {
    pub cert: SignedPublicKey,
    pub source: KeySource,
    pub url: String,
    pub fingerprint: String,
}

/// Try every automatic source for `email`.
pub async fn discover<F: KeyFetcher>(email: &str, fetcher: &F) -> Result<Discovery> {
    let email = email.trim();
    let (local, domain) = email.split_once('@').ok_or_else(|| {
        Error::new("bad_email", "That email address doesn't look right.")
            .with_hint("It should look like name@example.com.")
    })?;
    if local.is_empty() || domain.is_empty() {
        return Err(Error::new(
            "bad_email",
            "That email address doesn't look right.",
        ));
    }

    let mut last_error: Option<Error> = None;

    let urls = vec![
        (wkd::advanced_url(local, domain), KeySource::WebKeyDirectory),
        (wkd::direct_url(local, domain), KeySource::WebKeyDirectory),
        (keys_openpgp_url(email), KeySource::KeysOpenpgpOrg),
    ];

    for (url_result, source) in urls {
        let url = match url_result {
            Ok(url) => url,
            Err(e) => {
                last_error = Some(e);
                continue;
            }
        };
        match fetcher.fetch(&url).await {
            Ok(bytes) => {
                if let Ok(mut cert) = parse_public_key(&bytes) {
                    if cert_has_email(&cert, email) {
                        // WKD clients must discard every User ID other than
                        // the one that matched the requested address.
                        retain_matching_user_ids(&mut cert, email);
                        let fingerprint = raw_fingerprint(&cert.primary_key);
                        return Ok(Discovery {
                            cert,
                            source,
                            url,
                            fingerprint,
                        });
                    }
                    last_error = Some(
                        Error::new(
                            "wrong_user_id",
                            "The key published for that address doesn't list that address.",
                        )
                        .with_hint("Ask them to publish a key that includes their email address."),
                    );
                } else {
                    last_error = Some(Error::new(
                        "bad_key",
                        "The published key could not be read.",
                    ));
                }
            }
            Err(e) => {
                // A directory that needs authentication, or that we simply
                // could not reach, is not evidence that no key exists. Falling
                // back in that case could silently hand us a key from a less
                // trusted source, so stop instead.
                if e.code == "wkd_auth" || e.code == "network" {
                    return Err(e);
                }
                last_error = Some(e);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| Error::new("no_key", "No key could be found automatically.")))
}

/// keys.openpgp.org only returns keys with verified email addresses and strips
/// third-party signatures, so it does not leak social graphs.
pub fn keys_openpgp_url(email: &str) -> Result<String> {
    if !email.contains('@') {
        return Err(Error::new(
            "bad_email",
            "That email address doesn't look right.",
        ));
    }
    Ok(format!(
        "https://keys.openpgp.org/vks/v1/by-email/{}",
        form_urlencoded::byte_serialize(email.as_bytes()).collect::<String>()
    ))
}

/// Extract the address from a User ID, understanding the three shapes an
/// OpenPGP User ID can take: `addr`, `<addr>` and `Name <addr>`.
fn user_id_address(user_id: &str) -> Option<&str> {
    let text = user_id.trim();
    if text.starts_with('<') && text.ends_with('>') && text.len() >= 2 {
        return Some(text[1..text.len() - 1].trim());
    }
    if text.ends_with('>') {
        if let Some(start) = text.rfind('<') {
            return Some(text[start + 1..text.len() - 1].trim());
        }
        return None;
    }
    if text.contains('<') || text.contains('>') {
        return None;
    }
    Some(text)
}

/// Whether a User ID names exactly `email`. Substring matching is not safe:
/// `alice@example.com.attacker.net` contains `alice@example.com` but is a
/// different address.
pub fn user_id_matches(user_id: &str, email: &str) -> bool {
    user_id_address(user_id).is_some_and(|addr| addr.eq_ignore_ascii_case(email))
}

/// WKD requires the returned certificate to carry the requested User ID and
/// clients must discard every other User ID.
pub fn cert_has_email(cert: &SignedPublicKey, email: &str) -> bool {
    cert.details
        .users
        .iter()
        .any(|user| user_id_matches(&String::from_utf8_lossy(user.id.id()), email))
}

/// Drop all User IDs except the exact one the user asked for.
fn retain_matching_user_ids(cert: &mut SignedPublicKey, email: &str) {
    cert.details
        .users
        .retain(|user| user_id_matches(&String::from_utf8_lossy(user.id.id()), email));
}

/// A machine-readable description of a discovery result, for the native bridge.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoveryJson {
    pub fingerprint: String,
    pub source: String,
    pub url: String,
    pub armor: String,
}

impl From<Discovery> for DiscoveryJson {
    fn from(value: Discovery) -> Self {
        Self {
            fingerprint: value.fingerprint,
            source: format!("{:?}", value.source),
            url: value.url,
            armor: String::from_utf8_lossy(&value.cert.to_bytes().unwrap_or_default()).to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use newpgp_core::keys::generate_identity;

    struct MockFetcher {
        responses: std::collections::HashMap<String, Vec<u8>>,
    }

    #[async_trait::async_trait]
    impl KeyFetcher for MockFetcher {
        async fn fetch(&self, url: &str) -> Result<Vec<u8>> {
            self.responses
                .get(url)
                .cloned()
                .ok_or_else(|| Error::new("not_found", "No key at this address."))
        }
    }

    #[tokio::test]
    async fn discovers_from_wkd_advanced() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let armor = alice.public_armor().unwrap();
        let url = wkd::advanced_url("alice", "example.com").unwrap();
        let mut responses = std::collections::HashMap::new();
        responses.insert(url.clone(), armor.into_bytes());
        let fetcher = MockFetcher { responses };

        let found = discover("alice@example.com", &fetcher).await.unwrap();
        assert_eq!(found.source, KeySource::WebKeyDirectory);
        assert_eq!(found.url, url);
        assert_eq!(
            found.fingerprint,
            raw_fingerprint(&alice.public.primary_key)
        );
    }

    #[tokio::test]
    async fn falls_back_to_keys_openpgp() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let armor = alice.public_armor().unwrap();
        let url = keys_openpgp_url("alice@example.com").unwrap();
        let mut responses = std::collections::HashMap::new();
        responses.insert(url, armor.into_bytes());
        let fetcher = MockFetcher { responses };

        let found = discover("alice@example.com", &fetcher).await.unwrap();
        assert_eq!(found.source, KeySource::KeysOpenpgpOrg);
    }

    #[tokio::test]
    async fn rejects_a_key_with_the_wrong_user_id() {
        let bob = generate_identity("Bob", "bob@example.com", "").unwrap();
        let armor = bob.public_armor().unwrap();
        let url = wkd::advanced_url("alice", "example.com").unwrap();
        let mut responses = std::collections::HashMap::new();
        responses.insert(url, armor.into_bytes());
        let fetcher = MockFetcher { responses };

        let err = discover("alice@example.com", &fetcher).await.unwrap_err();
        assert!(err.friendly().to_ascii_lowercase().contains("address"));
    }

    #[tokio::test]
    async fn a_lookalike_user_id_is_rejected() {
        let attacker = generate_identity("Alice", "alice@example.com.attacker.net", "").unwrap();
        let url = wkd::advanced_url("alice", "example.com").unwrap();
        let mut responses = std::collections::HashMap::new();
        responses.insert(url, attacker.public_armor().unwrap().into_bytes());
        let fetcher = MockFetcher { responses };

        // Even though the attacker's address contains the requested one, the
        // certificate must never be returned for alice@example.com.
        assert!(discover("alice@example.com", &fetcher).await.is_err());
    }

    #[test]
    fn unrelated_user_ids_are_dropped_from_the_result() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let attacker = generate_identity("Alice", "alice@example.com.attacker.net", "").unwrap();
        let mut cert = alice.public.clone();
        cert.details
            .users
            .push(attacker.public.details.users[0].clone());
        assert_eq!(cert.details.users.len(), 2);

        retain_matching_user_ids(&mut cert, "alice@example.com");
        assert_eq!(cert.details.users.len(), 1);
        assert!(user_id_matches(
            &String::from_utf8_lossy(cert.details.users[0].id.id()),
            "alice@example.com"
        ));
    }

    #[test]
    fn user_id_matching_is_exact_and_bracket_aware() {
        assert!(user_id_matches(
            "Alice <alice@example.com>",
            "alice@example.com"
        ));
        assert!(user_id_matches("<ALICE@example.com>", "alice@example.com"));
        assert!(user_id_matches("alice@example.com", "alice@example.com"));
        assert!(!user_id_matches(
            "Alice <alice@example.com.attacker.net>",
            "alice@example.com"
        ));
        assert!(!user_id_matches(
            "notalice@example.com",
            "alice@example.com"
        ));
    }

    #[test]
    fn http_statuses_are_not_all_treated_as_not_found() {
        assert!(status_error(200).is_none());
        assert_eq!(status_error(401).unwrap().code, "wkd_auth");
        assert_eq!(status_error(404).unwrap().code, "not_found");
        assert_eq!(status_error(500).unwrap().code, "network");
    }

    #[tokio::test]
    async fn authentication_failure_does_not_fall_back() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        struct AuthFetcher {
            armor: Vec<u8>,
        }
        #[async_trait::async_trait]
        impl KeyFetcher for AuthFetcher {
            async fn fetch(&self, url: &str) -> Result<Vec<u8>> {
                if url.contains("openpgpkey.") {
                    Err(Error::new("wkd_auth", "Authentication required."))
                } else if url.starts_with("https://keys.openpgp.org/") {
                    Ok(self.armor.clone())
                } else {
                    Err(Error::new("not_found", "No key at this address."))
                }
            }
        }
        let fetcher = AuthFetcher {
            armor: alice.public_armor().unwrap().into_bytes(),
        };
        let err = discover("alice@example.com", &fetcher).await.unwrap_err();
        assert_eq!(err.code, "wkd_auth");
    }

    #[tokio::test]
    async fn network_failure_does_not_fall_back() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        struct NetworkFetcher {
            armor: Vec<u8>,
        }
        #[async_trait::async_trait]
        impl KeyFetcher for NetworkFetcher {
            async fn fetch(&self, url: &str) -> Result<Vec<u8>> {
                if url.contains("openpgpkey.") {
                    Err(Error::new("network", "Server did not answer."))
                } else if url.starts_with("https://keys.openpgp.org/") {
                    Ok(self.armor.clone())
                } else {
                    Err(Error::new("not_found", "No key at this address."))
                }
            }
        }
        let fetcher = NetworkFetcher {
            armor: alice.public_armor().unwrap().into_bytes(),
        };
        let err = discover("alice@example.com", &fetcher).await.unwrap_err();
        assert_eq!(err.code, "network");
    }

    #[test]
    fn keys_openpgp_url_encodes_email() {
        assert!(keys_openpgp_url("a+b@example.com")
            .unwrap()
            .contains("a%2Bb%40example.com"));
    }
}
