// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Private, app-scoped storage for the on-device identity and contact keyring.
//!
//! Everything lives under the absolute `filesDir` path supplied by Kotlin via
//! `NewPgpNative.init(...)`. Android's app sandbox is the first line of defence;
//! on Unix-like systems we additionally use 0700 directories and 0600 files.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use newpgp_core::{Error, Result};
use newpgp_keyring::KeyRing;
use serde::{Deserialize, Serialize};

pub(crate) const SUBDIR: &str = "newpgp";
pub(crate) const IDENTITY_FILE: &str = "identity.json";
pub(crate) const KEYRING_FILE: &str = "keyring.json";

static ROOT: Mutex<Option<PathBuf>> = Mutex::new(None);

/// The identity record kept on disk. The `private_key` armored block is the
/// sensitive part: it is S2K-encrypted only when the user chose a password.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredIdentity {
    pub email: String,
    pub fingerprint: String,
    pub public_key: String,
    pub private_key: String,
}

/// Remember the absolute path to the app-private files directory.
///
/// This must be called by Kotlin before any other bridge method. Re-calling it
/// with a different directory is allowed (useful in tests); calls from multiple
/// threads are serialized by the mutex.
pub fn init(files_dir_abs: &str) -> Result<()> {
    if files_dir_abs.trim().is_empty() {
        return Err(Error::new(
            "bad_storage_path",
            "The keyboard's private storage path is missing. Reopen the keyboard and try again.",
        ));
    }
    let root = PathBuf::from(files_dir_abs);
    if !root.is_absolute() {
        return Err(Error::new(
            "bad_storage_path",
            "The keyboard's private storage path must be absolute.",
        ));
    }
    let dir = root.join(SUBDIR);
    fs::create_dir_all(&dir).map_err(|_| {
        Error::new(
            "storage_setup",
            "Could not prepare private storage for your keys.",
        )
        .with_hint("Check that the keyboard has its normal app-private storage available.")
    })?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&dir, fs::Permissions::from_mode(0o700));
    }

    let mut guard = ROOT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = Some(root);
    Ok(())
}

/// The configured root directory, or a friendly error when `init` was skipped.
pub(crate) fn root() -> Result<PathBuf> {
    let guard = ROOT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.clone().ok_or_else(|| {
        Error::new(
            "not_initialized",
            "The keyboard's private storage has not been set up yet.",
        )
        .with_hint("Reopen the keyboard so it can initialise its key store.")
    })
}

pub(crate) fn identity_path() -> Result<PathBuf> {
    Ok(root()?.join(SUBDIR).join(IDENTITY_FILE))
}

pub(crate) fn keyring_path() -> Result<PathBuf> {
    Ok(root()?.join(SUBDIR).join(KEYRING_FILE))
}

pub(crate) fn keyring() -> Result<KeyRing> {
    KeyRing::open(&keyring_path()?)
}

/// Load the stored identity, with errors written for a non-technical reader.
pub(crate) fn load_identity() -> Result<StoredIdentity> {
    let path = identity_path()?;
    let bytes = fs::read(&path).map_err(|_| {
        Error::new(
            "no_identity",
            "There is no NewPGP identity on this device yet.",
        )
        .with_hint("Create one in the keyboard settings before encrypting.")
    })?;
    serde_json::from_slice(&bytes).map_err(|_| {
        Error::new(
            "identity_corrupt",
            "Your stored NewPGP identity could not be read.",
        )
        .with_hint("Create a new identity in settings; the old file was left in place.")
    })
}

pub(crate) fn save_identity(identity: &StoredIdentity) -> Result<()> {
    let path = identity_path()?;
    let bytes = serde_json::to_vec_pretty(identity)
        .map_err(|_| Error::new("identity_write", "Could not prepare your identity for storage."))?;
    write_atomic(&path, &bytes)
}

/// Write a small file privately: unique temporary name, restrictive mode,
/// then an atomic replace. Public keys and keyring data are not secret, but we
/// apply the same care to everything in this directory.
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
    fs::create_dir_all(parent).map_err(|_| {
        Error::new("storage_setup", "Could not create the keyboard's private folder.")
    })?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
    }

    let tmp = path.with_extension("tmp");
    {
        let mut file = open_private(&tmp).map_err(|_| {
            Error::new("storage_write", "Could not save your NewPGP keys on this device.")
        })?;
        file.write_all(bytes).map_err(|_| {
            Error::new("storage_write", "Could not save your NewPGP keys on this device.")
        })?;
        file.flush().map_err(|_| {
            Error::new("storage_write", "Could not save your NewPGP keys on this device.")
        })?;
    }

    // POSIX rename replaces atomically. Windows needs the old file gone first;
    // this crate is built for Android, but host tests run on Windows too.
    #[cfg(not(unix))]
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    fs::rename(&tmp, path).map_err(|_| {
        Error::new("storage_write", "Could not finalize saving your NewPGP keys.")
    })
}

#[cfg(unix)]
fn open_private(path: &Path) -> std::io::Result<fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)
}

#[cfg(not(unix))]
fn open_private(path: &Path) -> std::io::Result<fs::File> {
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)
}
