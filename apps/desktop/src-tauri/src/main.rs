// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! NewPGP desktop backend. Thin wrapper over the tested Rust crates.

use std::path::PathBuf;

use newpgp_core::keys::{
    generate_identity, parse_public_key, parse_secret_key, raw_fingerprint,
};
use newpgp_core::message::{decrypt_text, encrypt_text, EncryptionProfile};
use newpgp_keyring::{KeyRing, KeySource};
use newpgp_mail::{compose, ComposeMode, ComposeRequest};
use pgp::composed::{ArmorOptions, SignedPublicKey, SignedSecretKey};
use serde_json::{json, Value};
use tauri::Manager;

/// `newpgp_core::Result` is a one-parameter alias for internal crates. Tauri
/// commands cannot use it: they must return a serializable `Result<T, String>`.
type CoreResult<T> = std::result::Result<T, newpgp_core::Error>;

fn friendly(error: newpgp_core::Error) -> String {
    error.friendly()
}

fn data_dir(app: &tauri::AppHandle) -> CoreResult<PathBuf> {
    app.path()
        .app_data_dir()
        .map_err(|e| newpgp_core::Error::new("app_data", "Could not find the app data folder.").with_hint(e.to_string()))
}

fn identity_path(dir: &PathBuf) -> PathBuf {
    dir.join("identity.asc")
}

fn load_secret(dir: &PathBuf) -> CoreResult<SignedSecretKey> {
    let bytes = std::fs::read(identity_path(dir)).map_err(|_| {
        newpgp_core::Error::new("no_key", "You haven't created your key yet.")
            .with_hint("Open the Keys tab and create one.")
    })?;
    parse_secret_key(&bytes)
}

fn keyring(dir: &PathBuf) -> CoreResult<KeyRing> {
    KeyRing::open(&dir.join("contacts.json"))
}

#[tauri::command]
fn create_identity(app: tauri::AppHandle, name: String, email: String, password: String) -> Result<Value, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    std::fs::create_dir_all(&dir).map_err(|e| friendly(newpgp_core::Error::new("dir", "Could not create app data.").with_hint(e.to_string())))?;
    let identity = generate_identity(&name, &email, &password).map_err(friendly)?;
    std::fs::write(identity_path(&dir), identity.secret_armor().map_err(friendly)?)
        .map_err(|e| friendly(newpgp_core::Error::new("save", "Could not save your key.").with_hint(e.to_string())))?;
    let mut ring = keyring(&dir).map_err(friendly)?;
    ring.set_own_identity(&identity.public).map_err(friendly)?;
    ring.save().map_err(friendly)?;
    Ok(json!({
        "fingerprint": identity.fingerprint,
        "publicKey": identity.public_armor().map_err(friendly)?,
        "email": email,
    }))
}

#[tauri::command]
fn my_key(app: tauri::AppHandle) -> Result<Value, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let secret = load_secret(&dir).map_err(friendly)?;
    let public = SignedPublicKey::from(secret.clone());
    Ok(json!({
        "fingerprint": raw_fingerprint(&secret.primary_key),
        "prettyFingerprint": newpgp_core::keys::format_fingerprint(&secret.primary_key),
        "publicKey": public.to_armored_string(ArmorOptions::default()).map_err(|e| {
            friendly(newpgp_core::Error::new("export", "Could not export your public key.").with_hint(e.to_string()))
        })?,
    }))
}

#[tauri::command]
fn import_key(app: tauri::AppHandle, email: String, armor: String) -> Result<Value, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let cert = parse_public_key(armor.as_bytes()).map_err(friendly)?;
    let mut ring = keyring(&dir).map_err(friendly)?;
    ring.learn(&email, &cert, KeySource::Manual).map_err(friendly)?;
    ring.save().map_err(friendly)?;
    Ok(json!({ "fingerprint": raw_fingerprint(&cert), "safetyNumber": ring.safety_number_for(&email).map_err(friendly)? }))
}

#[tauri::command]
fn contacts(app: tauri::AppHandle) -> Result<Value, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let ring = keyring(&dir).map_err(friendly)?;
    let list: Vec<Value> = ring
        .contacts()
        .map(|c| {
            json!({
                "email": c.email,
                "fingerprint": c.pretty_fingerprint(),
                "source": c.source.label(),
                "verification": c.verification.label(),
            })
        })
        .collect();
    Ok(json!(list))
}

#[tauri::command]
fn verify_contact(app: tauri::AppHandle, email: String, code: String) -> Result<(), String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let mut ring = keyring(&dir).map_err(friendly)?;
    ring.verify(&email, &code).map_err(friendly)?;
    ring.save().map_err(friendly)?;
    Ok(())
}

#[tauri::command]
fn encrypt_message(app: tauri::AppHandle, plaintext: String, recipients: Vec<String>, aead: bool) -> Result<String, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let secret = load_secret(&dir).map_err(friendly)?;
    let ring = keyring(&dir).map_err(friendly)?;
    let certs = recipients
        .iter()
        .map(|email| {
            let contact = ring.get(email).ok_or_else(|| {
                newpgp_core::Error::new("missing_key", format!("No key saved for {email}."))
                    .with_hint("Discover or import their key first.")
            })?;
            contact.public_key()
        })
        .collect::<CoreResult<Vec<_>>>()
        .map_err(friendly)?;
    let signer = (&secret, "");
    encrypt_text(
        &plaintext,
        &certs,
        Some(signer),
        if aead { EncryptionProfile::Aead } else { EncryptionProfile::Compatible },
    )
    .map_err(friendly)
}

#[tauri::command]
fn decrypt_message(app: tauri::AppHandle, armored: String) -> Result<Value, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let secret = load_secret(&dir).map_err(friendly)?;
    let ring = keyring(&dir).map_err(friendly)?;
    let known: Vec<SignedPublicKey> = ring.contacts().filter_map(|c| c.public_key().ok()).collect();
    let (text, status) = decrypt_text(armored.as_bytes(), &secret, "", &known).map_err(friendly)?;
    Ok(json!({ "text": text, "signature": status.label() }))
}

#[tauri::command]
fn compose_message(app: tauri::AppHandle, body: String, recipients: Vec<String>, mode: String) -> Result<Value, String> {
    let dir = data_dir(&app).map_err(friendly)?;
    let secret = load_secret(&dir).map_err(friendly)?;
    let ring = keyring(&dir).map_err(friendly)?;
    let certs = recipients
        .iter()
        .filter_map(|email| ring.get(email))
        .filter_map(|contact| contact.public_key().ok())
        .collect::<Vec<_>>();
    let mode = match mode.as_str() {
        "pgp-only" => ComposeMode::PgpOnly,
        "plain" => ComposeMode::PlainOnly,
        _ => ComposeMode::SendBoth,
    };
    let result = compose(ComposeRequest {
        body: &body,
        subject: "",
        from_addr: "",
        recipients: &certs,
        signer: Some((&secret, "")),
        mode,
        prefer_encrypt_mutual: false,
        profile: EncryptionProfile::Compatible,
    })
    .map_err(friendly)?;
    Ok(json!({ "body": result.body, "notices": result.notices }))
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            create_identity,
            my_key,
            import_key,
            contacts,
            verify_contact,
            encrypt_message,
            decrypt_message,
            compose_message
        ])
        .run(tauri::generate_context!())
        .expect("error while running NewPGP");
}
