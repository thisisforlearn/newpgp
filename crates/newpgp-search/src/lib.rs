// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Search inside encrypted email, locally only.
//!
//! The index stores each message as an AES-256-GCM ciphertext. Nothing is sent
//! to a server, so the webmail provider cannot see what you search for. The
//! key is supplied by the app (normally from the OS keychain).

use std::io::Write;
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

    /// Persist the index atomically, using a uniquely named temporary file and
    /// an atomic replace so the original is never deleted before the new index
    /// is ready (Windows included).
    pub fn save(&self) -> Result<()> {
        let parent = self
            .path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        std::fs::create_dir_all(parent).map_err(|e| {
            Error::new("index_write", "Could not create the index folder.").with_hint(e.to_string())
        })?;
        let bytes = serde_json::to_vec(&IndexFile {
            entries: self.entries.clone(),
        })
        .map_err(|_| Error::new("index_write", "Could not serialize the search index."))?;
        let mut tmp = tempfile::NamedTempFile::new_in(parent).map_err(|e| {
            Error::new("index_write", "Could not save the search index.").with_hint(e.to_string())
        })?;
        tmp.write_all(&bytes).map_err(|e| {
            Error::new("index_write", "Could not save the search index.").with_hint(e.to_string())
        })?;
        tmp.persist(&self.path).map_err(|e| {
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
    // `pos` and `needle.len()` are byte offsets. Round both window edges down
    // (start) / up (end) to a UTF-8 character boundary before slicing.
    let mut start = pos.saturating_sub(30);
    while start > 0 && !body.is_char_boundary(start) {
        start -= 1;
    }
    let mut end = (pos + needle.len() + 50).min(body.len());
    while end < body.len() && !body.is_char_boundary(end) {
        end += 1;
    }
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
    fn snippet_with_multibyte_characters_does_not_panic() {
        let dir = tempdir().unwrap();
        let body = format!("{}needle", "😀".repeat(20));
        // The private helper is exercised through `search`, where the byte
        // offset used to land in the middle of an emoji.
        let mut index = SearchIndex::open(&dir.path().join("i"), [4u8; 32]).unwrap();
        index.upsert(&summary("emoji", &body)).unwrap();
        let hits = index.search("needle").unwrap();
        assert_eq!(hits.len(), 1);
        assert!(hits[0].snippet.contains("needle"));
    }

    #[test]
    fn repeated_saves_replace_the_existing_index() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("i");
        let mut index = SearchIndex::open(&path, [5u8; 32]).unwrap();
        index.upsert(&summary("1", "first text")).unwrap();
        index.save().unwrap();
        index.upsert(&summary("1", "second text")).unwrap();
        index.save().unwrap();
        let reloaded = SearchIndex::open(&path, [5u8; 32]).unwrap();
        assert_eq!(reloaded.len(), 1);
        assert_eq!(reloaded.search("second").unwrap().len(), 1);
        assert!(reloaded.search("first").unwrap().is_empty());
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
