// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Search inside encrypted email, locally only.
//!
//! The index stores each message as an AES-256-GCM ciphertext. Nothing is sent
//! to a server, so the webmail provider cannot see what you search for. The
//! key is supplied by the app (normally from the OS keychain).

use std::path::{Path, PathBuf};

use aes_gcm::aead::{Aead, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use newpgp_core::{Error, Result};
use rand::RngCore;
use serde::{Deserialize, Serialize};

const NONCE_LEN: usize = 12;

/// The fields we keep for a message. All of them are inside the ciphertext.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessageSummary {
    pub id: String,
    pub from: String,
    pub subject: String,
    pub body: String,
    pub timestamp: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredEntry {
    id: String,
    nonce: Vec<u8>,
    ciphertext: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct IndexFile {
    entries: Vec<StoredEntry>,
}

/// What a search matched.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchHit {
    pub id: String,
    pub from: String,
    pub subject: String,
    pub snippet: String,
    pub timestamp: u64,
}

/// A local encrypted index.
pub struct SearchIndex {
    path: PathBuf,
    key: [u8; 32],
    entries: Vec<StoredEntry>,
}

impl SearchIndex {
    /// Open (or create) an index at `path`, encrypted with `key`.
    pub fn open(path: &Path, key: [u8; 32]) -> Result<Self> {
        let entries = if path.exists() {
            let bytes = std::fs::read(path).map_err(|e| {
                Error::new("index_read", "Could not open the local search index.")
                    .with_hint(e.to_string())
            })?;
            let file: IndexFile = serde_json::from_slice(&bytes).map_err(|e| {
                Error::new("index_corrupt", "The local search index could not be read.")
                    .with_hint(
                        "It will be rebuilt the next time messages sync. Your mail is unaffected.",
                    )
                    .with_hint(e.to_string())
            })?;
            file.entries
        } else {
            Vec::new()
        };
        Ok(Self {
            path: path.to_path_buf(),
            key,
            entries,
        })
    }

    /// Add or replace a message in the index.
    pub fn upsert(&mut self, summary: &MessageSummary) -> Result<()> {
        let cipher = Aes256Gcm::new_from_slice(&self.key)
            .map_err(|_| Error::new("bad_index_key", "The search key is not valid for AES-256."))?;
        let mut nonce_bytes = [0u8; NONCE_LEN];
        OsRng.fill_bytes(&mut nonce_bytes);
        let plaintext = serde_json::to_vec(summary).map_err(|_| {
            Error::new(
                "index_write",
                "Could not prepare that message for the index.",
            )
        })?;
        let ciphertext = cipher
            .encrypt(Nonce::from_slice(&nonce_bytes), plaintext.as_ref())
            .map_err(|_| {
                Error::new(
                    "index_write",
                    "Could not encrypt that message for the index.",
                )
            })?;

        self.entries.retain(|entry| entry.id != summary.id);
        self.entries.push(StoredEntry {
            id: summary.id.clone(),
            nonce: nonce_bytes.to_vec(),
            ciphertext,
        });
        Ok(())
    }

    /// Search decrypted content. The query never leaves the device.
    pub fn search(&self, query: &str) -> Result<Vec<SearchHit>> {
        let needle = query.trim().to_ascii_lowercase();
        if needle.is_empty() {
            return Ok(Vec::new());
        }
        let cipher = Aes256Gcm::new_from_slice(&self.key)
            .map_err(|_| Error::new("bad_index_key", "The search key is not valid for AES-256."))?;

        let mut hits = Vec::new();
        for entry in &self.entries {
            let plaintext = cipher
                .decrypt(Nonce::from_slice(&entry.nonce), entry.ciphertext.as_ref())
                .map_err(|_| {
                    Error::new(
                        "index_decrypt",
                        "Part of the local search index could not be decrypted.",
                    )
                    .with_hint("It will be rebuilt on the next sync.")
                })?;
            let summary: MessageSummary = serde_json::from_slice(&plaintext)
                .map_err(|_| Error::new("index_decrypt", "Part of the local index is damaged."))?;

            let haystack = format!("{} {} {}", summary.from, summary.subject, summary.body)
                .to_ascii_lowercase();
            if haystack.contains(&needle) {
                hits.push(SearchHit {
                    id: summary.id,
                    from: summary.from,
                    subject: summary.subject,
                    snippet: snippet(&summary.body, &needle),
                    timestamp: summary.timestamp,
                });
            }
        }
        hits.sort_by_key(|hit| std::cmp::Reverse(hit.timestamp));
        Ok(hits)
    }

    /// Persist the index atomically.
    pub fn save(&self) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| {
                Error::new("index_write", "Could not create the index folder.")
                    .with_hint(e.to_string())
            })?;
        }
        let bytes = serde_json::to_vec(&IndexFile {
            entries: self.entries.clone(),
        })
        .map_err(|_| Error::new("index_write", "Could not serialize the search index."))?;
        let tmp = self.path.with_extension("tmp");
        std::fs::write(&tmp, bytes).map_err(|e| {
            Error::new("index_write", "Could not save the search index.").with_hint(e.to_string())
        })?;
        #[cfg(windows)]
        if self.path.exists() {
            std::fs::remove_file(&self.path).map_err(|e| {
                Error::new("index_write", "Could not replace the search index.")
                    .with_hint(e.to_string())
            })?;
        }
        std::fs::rename(&tmp, &self.path).map_err(|e| {
            Error::new("index_write", "Could not finalize the search index.")
                .with_hint(e.to_string())
        })?;
        Ok(())
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

fn snippet(body: &str, needle: &str) -> String {
    let lower = body.to_ascii_lowercase();
    let Some(pos) = lower.find(needle) else {
        return body.chars().take(80).collect();
    };
    let start = pos.saturating_sub(30);
    let end = (pos + needle.len() + 50).min(body.len());
    let mut out = String::new();
    if start > 0 {
        out.push('…');
    }
    out.push_str(&body[start..end]);
    if end < body.len() {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn summary(id: &str, body: &str) -> MessageSummary {
        MessageSummary {
            id: id.to_string(),
            from: "alice@example.com".to_string(),
            subject: "Lunch plans".to_string(),
            body: body.to_string(),
            timestamp: 1234,
        }
    }

    #[test]
    fn searches_inside_encrypted_body_after_reload() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("index.bin");
        let key = [7u8; 32];
        {
            let mut index = SearchIndex::open(&path, key).unwrap();
            index
                .upsert(&summary("1", "The meeting is at the rooftop cafe"))
                .unwrap();
            index.save().unwrap();
        }
        let index = SearchIndex::open(&path, key).unwrap();
        let hits = index.search("rooftop").unwrap();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, "1");
        assert!(hits[0].snippet.to_ascii_lowercase().contains("rooftop"));
        assert!(index.search("pizza").unwrap().is_empty());
        assert!(index.search("   ").unwrap().is_empty());
    }

    #[test]
    fn upsert_replaces_same_id() {
        let dir = tempdir().unwrap();
        let mut index = SearchIndex::open(&dir.path().join("i"), [1u8; 32]).unwrap();
        index.upsert(&summary("1", "old text")).unwrap();
        index.upsert(&summary("1", "new text")).unwrap();
        assert_eq!(index.len(), 1);
        assert!(index.search("old").unwrap().is_empty());
        assert_eq!(index.search("new").unwrap().len(), 1);
    }

    #[test]
    fn wrong_key_cannot_decrypt() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("i");
        let mut index = SearchIndex::open(&path, [2u8; 32]).unwrap();
        index.upsert(&summary("1", "secret text")).unwrap();
        index.save().unwrap();
        let other = SearchIndex::open(&path, [3u8; 32]).unwrap();
        assert!(other.search("secret").is_err());
    }
}
