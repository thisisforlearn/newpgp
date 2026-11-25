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

        if !response.status().is_success() {
            return Err(Error::new(
                "not_found",
                "No published key was found for that address.",
            )
            .with_hint("They can send you an invite link, or you can use an Autocrypt-enabled mail app."));
        }

        response.bytes().await.map(|b| b.to_vec()).map_err(|e| {
            Error::new("network", "The key download was interrupted.").with_hint(e.to_string())
        })
    }
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
                if let Ok(cert) = parse_public_key(&bytes) {
                    if cert_has_email(&cert, email) {
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
            Err(e) => last_error = Some(e),
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

/// WKD requires the returned certificate to carry the requested User ID and
/// clients must discard every other User ID.
pub fn cert_has_email(cert: &SignedPublicKey, email: &str) -> bool {
    cert.details.users.iter().any(|user| {
        let text = String::from_utf8_lossy(user.id.id());
        text.eq_ignore_ascii_case(email)
            || text.eq_ignore_ascii_case(&format!("<{email}>"))
            || text
                .to_ascii_lowercase()
                .contains(&email.to_ascii_lowercase())
    })
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

    #[test]
    fn keys_openpgp_url_encodes_email() {
        assert!(keys_openpgp_url("a+b@example.com")
            .unwrap()
            .contains("a%2Bb%40example.com"));
    }
}
