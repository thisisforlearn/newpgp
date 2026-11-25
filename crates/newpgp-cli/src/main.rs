// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! NewPGP command line app and native messaging bridge.
//!
//! The bridge speaks Chrome's 4-byte little-endian native messaging protocol
//! on stdin/stdout. All diagnostics go to stderr, never stdout.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use clap::{Parser, Subcommand};
use newpgp_backup::{
    create_public_key_backup, create_setup_code_backup, generate_setup_code,
    restore_public_key_backup, restore_setup_code_backup,
};
use newpgp_core::armor::normalize_armor;
use newpgp_core::files::{decrypt_file, encrypt_file};
use newpgp_core::keys::{
    generate_identity, parse_public_key, parse_secret_key, raw_fingerprint, Identity,
};
use newpgp_core::message::{decrypt_bytes, encrypt_text, EncryptionProfile, SignatureStatus};
use newpgp_core::{Error, Result};
use newpgp_discovery::invite::Invite;
use newpgp_discovery::{discover, HttpFetcher};
use newpgp_keyring::{KeyRing, KeySource, TofuOutcome};
use newpgp_mail::{compose, ComposeMode, ComposeRequest};
use newpgp_search::{MessageSummary, SearchIndex};
use pgp::composed::{Deserializable, DetachedSignature, SignedPublicKey};
use pgp::crypto::hash::HashAlgorithm;
use pgp::types::Password;
use rand::rngs::OsRng;
use serde::Deserialize;
use serde_json::{json, Value};
use tracing_subscriber::EnvFilter;

#[derive(Debug, Parser)]
#[command(
    name = "newpgp",
    about = "Friendly OpenPGP for everyone. Private by default, simple by design.",
    version
)]
struct Cli {
    /// Where keys and the local index are stored.
    #[arg(long, env = "NEWPGP_HOME")]
    home: Option<PathBuf>,

    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Create your key pair.
    Init {
        #[arg(long)]
        name: String,
        #[arg(long)]
        email: String,
        /// Password for the key. Leave empty for no password.
        #[arg(long, env = "NEWPGP_KEY_PASSWORD", default_value = "")]
        password: String,
    },
    /// Manage contact keys.
    Key {
        #[command(subcommand)]
        action: KeyCommand,
    },
    /// Encrypt a file to one or more recipients.
    Encrypt {
        /// Email addresses, fingerprints or armored public keys.
        #[arg(short = 'r', long = "to", required = true)]
        recipients: Vec<String>,
        #[arg(short, long)]
        file: PathBuf,
        #[arg(short, long)]
        output: Option<PathBuf>,
        #[arg(long)]
        sign: bool,
        #[arg(long, help = "Use RFC 9580 AEAD (newer clients only)")]
        aead: bool,
    },
    /// Decrypt a file.
    Decrypt {
        #[arg(short, long)]
        file: PathBuf,
        #[arg(short, long)]
        output: Option<PathBuf>,
        #[arg(long, env = "NEWPGP_KEY_PASSWORD", default_value = "")]
        password: String,
    },
    /// Compose an email body with the friendly send-both default.
    Compose {
        #[arg(short, long)]
        body: String,
        #[arg(short = 'r', long = "to", required = true)]
        recipients: Vec<String>,
        #[arg(long, value_enum, default_value_t = ModeArg::Both)]
        mode: ModeArg,
    },
    /// Search decrypted mail locally.
    Search {
        query: String,
        #[arg(short, long)]
        id: Option<String>,
        #[arg(short, long)]
        from: Option<String>,
        #[arg(short, long)]
        subject: Option<String>,
        #[arg(short, long)]
        body: Option<String>,
    },
    /// Create or restore key backups.
    Backup {
        #[command(subcommand)]
        action: BackupCommand,
    },
    /// Run as a native messaging host for the browser extension.
    Bridge,
}

#[derive(Debug, clap::ValueEnum, Clone, Copy)]
enum ModeArg {
    Both,
    PgpOnly,
    Plain,
}

impl From<ModeArg> for ComposeMode {
    fn from(value: ModeArg) -> Self {
        match value {
            ModeArg::Both => Self::SendBoth,
            ModeArg::PgpOnly => Self::PgpOnly,
            ModeArg::Plain => Self::PlainOnly,
        }
    }
}

#[derive(Debug, Subcommand)]
enum KeyCommand {
    /// Show your public key and fingerprint.
    Show,
    /// Show all contacts and their verification status.
    List,
    /// Look up a key automatically (WKD, keys.openpgp.org).
    Find { email: String },
    /// Import an armored public key for an address.
    Import { email: String, file: PathBuf },
    /// Create an invite link for someone with no published key.
    Invite { email: String },
    /// Verify a contact using a safety number read out of band.
    Verify { email: String, code: String },
}

#[derive(Debug, Subcommand)]
enum BackupCommand {
    /// Encrypt your secret key with a randomly generated 36-digit setup code.
    Create { output: PathBuf },
    /// Encrypt your secret key to a recipient's public key (no code needed).
    CreateForRecipient { email: String, output: PathBuf },
    /// Restore a setup-code backup.
    Restore {
        file: PathBuf,
        code: String,
        output: PathBuf,
    },
    /// Restore a public-key backup.
    RestoreWithKey {
        file: PathBuf,
        output: PathBuf,
        password: String,
    },
}

fn home_dir(cli: &Cli) -> PathBuf {
    cli.home.clone().unwrap_or_else(|| {
        dirs::data_local_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("newpgp")
    })
}

fn identity_path(home: &Path) -> PathBuf {
    home.join("identity.asc")
}
fn keyring_path(home: &Path) -> PathBuf {
    home.join("contacts.json")
}
fn index_path(home: &Path) -> PathBuf {
    home.join("search-index.bin")
}

fn load_identity(home: &Path) -> Result<Identity> {
    let path = identity_path(home);
    let bytes = std::fs::read(&path).map_err(|_| {
        Error::new("no_identity", "You haven't created a key yet.")
            .with_hint("Run: newpgp init --name \"Your Name\" --email you@example.com")
    })?;
    let secret = parse_secret_key(&bytes)?;
    let public = SignedPublicKey::from(secret.clone());
    Ok(Identity {
        fingerprint: raw_fingerprint(&secret.primary_key),
        secret,
        public,
    })
}

fn load_keyring(home: &Path) -> Result<KeyRing> {
    KeyRing::open(&keyring_path(home))
}

/// Resolve a recipient string to a certificate.
fn resolve_recipient(value: &str, ring: &KeyRing) -> Result<SignedPublicKey> {
    if value.trim_start().starts_with("-----BEGIN PGP ") {
        return parse_public_key(value.as_bytes());
    }
    if let Some(contact) = ring.get(value) {
        return contact.public_key();
    }
    Err(
        Error::new("unknown_recipient", format!("No key found for '{value}'."))
            .with_hint("Run `newpgp key find <email>` or give them an invite link."),
    )
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("warn")),
        )
        .with_writer(std::io::stderr)
        .init();

    let cli = Cli::parse();
    let home = home_dir(&cli);
    let result = run(&cli, &home).await;
    if let Err(error) = result {
        eprintln!("{}", error.friendly());
        if let Some(hint) = &error.hint {
            eprintln!("Hint: {hint}");
        }
        std::process::exit(1);
    }
}

async fn run(cli: &Cli, home: &Path) -> Result<()> {
    match &cli.command {
        Command::Init {
            name,
            email,
            password,
        } => {
            std::fs::create_dir_all(home).map_err(|e| {
                Error::new("home", "Could not create the data folder.").with_hint(e.to_string())
            })?;
            let identity = generate_identity(name, email, password)?;
            std::fs::write(identity_path(home), identity.secret_armor()?).map_err(|e| {
                Error::new("save_identity", "Could not save your key.").with_hint(e.to_string())
            })?;
            let mut ring = load_keyring(home)?;
            ring.set_own_identity(&identity.public)?;
            ring.save()?;
            println!("Your key is ready.");
            println!("Fingerprint: {}", identity.fingerprint);
            println!(
                "\nPublic key (safe to share):\n{}",
                identity.public_armor()?
            );
            if !password.is_empty() {
                println!("\nKeep your password safe. There is no way to recover it.");
            }
            Ok(())
        }
        Command::Key { action } => key_command(home, action).await,
        Command::Encrypt {
            recipients,
            file,
            output,
            sign,
            aead,
        } => {
            let identity = load_identity(home)?;
            let ring = load_keyring(home)?;
            let certs: Vec<SignedPublicKey> = recipients
                .iter()
                .map(|r| resolve_recipient(r, &ring))
                .collect::<Result<Vec<_>>>()?;
            let profile = if *aead {
                EncryptionProfile::Aead
            } else {
                EncryptionProfile::Compatible
            };
            let signer = if *sign {
                Some((&identity.secret, ""))
            } else {
                None
            };
            let out = output.clone().unwrap_or_else(|| {
                file.with_extension(format!(
                    "{}.pgp",
                    file.extension().and_then(|e| e.to_str()).unwrap_or("")
                ))
            });
            let bytes = encrypt_file(file, &out, &certs, signer, profile)?;
            println!("Encrypted {bytes} bytes to {}", out.display());
            Ok(())
        }
        Command::Decrypt {
            file,
            output,
            password,
        } => {
            let identity = load_identity(home)?;
            let ring = load_keyring(home)?;
            let known: Vec<SignedPublicKey> = ring
                .contacts()
                .filter_map(|c| c.public_key().ok())
                .collect();
            let (path, result) =
                decrypt_file(file, output.as_deref(), &identity.secret, password, &known)?;
            println!("Decrypted to {}", path.display());
            println!("Signature: {}", result.signature.label());
            Ok(())
        }
        Command::Compose {
            body,
            recipients,
            mode,
        } => {
            let identity = load_identity(home)?;
            let ring = load_keyring(home)?;
            let certs: Vec<SignedPublicKey> = recipients
                .iter()
                .map(|r| resolve_recipient(r, &ring))
                .collect::<Result<Vec<_>>>()?;
            let composed = compose(ComposeRequest {
                body,
                subject: "",
                from_addr: "",
                recipients: &certs,
                signer: Some((&identity.secret, "")),
                mode: (*mode).into(),
                prefer_encrypt_mutual: false,
                profile: EncryptionProfile::Compatible,
            })?;
            for notice in &composed.notices {
                eprintln!("Note: {notice}");
            }
            if let Some(header) = composed.autocrypt_header {
                eprintln!("{header}");
            }
            println!("{}", composed.body);
            Ok(())
        }
        Command::Search {
            query,
            id,
            from,
            subject,
            body,
        } => {
            // The key comes from the OS keychain in the GUI. For the CLI we use
            // a key file so the index is never readable with a constant key.
            let key = load_index_key(home)?;
            let index = SearchIndex::open(&index_path(home), key)?;
            if let (Some(id), Some(fr), Some(sub), Some(bd)) = (id, from, subject, body) {
                let mut index = index;
                index.upsert(&MessageSummary {
                    id: id.clone(),
                    from: fr.clone(),
                    subject: sub.clone(),
                    body: bd.clone(),
                    timestamp: 0,
                })?;
                index.save()?;
                println!("Indexed message {id}");
                return Ok(());
            }
            for hit in index.search(query)? {
                println!("{}\t{}\t{}\t{}", hit.id, hit.from, hit.subject, hit.snippet);
            }
            Ok(())
        }
        Command::Backup { action } => backup_command(home, action),
        Command::Bridge => bridge(home).await,
    }
}

fn load_index_key(home: &Path) -> Result<[u8; 32]> {
    let path = home.join("index-key.bin");
    if path.exists() {
        let bytes = std::fs::read(&path).map_err(|e| {
            Error::new("index_key", "Could not read the index key.").with_hint(e.to_string())
        })?;
        if bytes.len() == 32 {
            let mut key = [0u8; 32];
            key.copy_from_slice(&bytes);
            return Ok(key);
        }
    }
    use rand::RngCore;
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    std::fs::create_dir_all(home).ok();
    std::fs::write(&path, key).map_err(|e| {
        Error::new("index_key", "Could not save the index key.").with_hint(e.to_string())
    })?;
    Ok(key)
}

async fn key_command(home: &Path, action: &KeyCommand) -> Result<()> {
    match action {
        KeyCommand::Show => {
            let identity = load_identity(home)?;
            println!("Fingerprint: {}", identity.fingerprint);
            println!(
                "Safety number with yourself: {}",
                newpgp_core::keys::safety_number(&identity.public, &identity.public)
            );
            println!("\n{}", identity.public_armor()?);
            Ok(())
        }
        KeyCommand::List => {
            let ring = load_keyring(home)?;
            for contact in ring.contacts() {
                println!(
                    "{}\t{}\t{}\t{}",
                    contact.email,
                    contact.pretty_fingerprint(),
                    contact.source.label(),
                    contact.verification.label()
                );
            }
            Ok(())
        }
        KeyCommand::Find { email } => {
            let found = discover(email, &HttpFetcher::new()).await?;
            let mut ring = load_keyring(home)?;
            let outcome = ring.learn(email, &found.cert, found.source)?;
            ring.save()?;
            match outcome {
                TofuOutcome::KeyChanged { .. } => {
                    println!("WARNING: this key is different from the one saved before.");
                    println!("Compare the safety number with them before using it.");
                }
                _ => println!("Found and saved a key for {email} via {}.", found.url),
            }
            println!("Fingerprint: {}", found.fingerprint);
            println!("Safety number: {}", ring.safety_number_for(email)?);
            Ok(())
        }
        KeyCommand::Import { email, file } => {
            let bytes = std::fs::read(file).map_err(|e| {
                Error::new("read", "Could not read that key file.").with_hint(e.to_string())
            })?;
            let cert = parse_public_key(&bytes)?;
            let mut ring = load_keyring(home)?;
            ring.learn(email, &cert, KeySource::Manual)?;
            ring.save()?;
            println!(
                "Saved key for {email}. Safety number: {}",
                ring.safety_number_for(email)?
            );
            Ok(())
        }
        KeyCommand::Invite { email } => {
            let identity = load_identity(home)?;
            let invite = Invite::new(email, &identity.fingerprint, Some(identity.public_armor()?))?;
            println!(
                "{}",
                invite.to_url(newpgp_discovery::invite::DEFAULT_INVITE_BASE)?
            );
            Ok(())
        }
        KeyCommand::Verify { email, code } => {
            let mut ring = load_keyring(home)?;
            ring.verify(email, code)?;
            ring.save()?;
            println!("Verified. You'll be warned if this key ever changes.");
            Ok(())
        }
    }
}

fn backup_command(home: &Path, action: &BackupCommand) -> Result<()> {
    let identity = load_identity(home)?;
    let secret_armor = identity.secret_armor()?;
    match action {
        BackupCommand::Create { output } => {
            let code = generate_setup_code();
            let backup = create_setup_code_backup(&secret_armor, &code)?;
            std::fs::write(output, backup).map_err(|e| {
                Error::new("write", "Could not save the backup.").with_hint(e.to_string())
            })?;
            println!("WRITE THIS DOWN AND KEEP IT SEPARATE FROM THE FILE:");
            println!("{}\n", code.as_str());
            println!("Never send the code in the same email as the backup.");
            Ok(())
        }
        BackupCommand::CreateForRecipient { email, output } => {
            let ring = load_keyring(home)?;
            let cert = resolve_recipient(email, &ring)?;
            let armor = cert.to_armored_string(Default::default()).map_err(|e| {
                Error::new("export", "Could not export that key.").with_hint(e.to_string())
            })?;
            let backup = create_public_key_backup(&secret_armor, &armor)?;
            std::fs::write(output, backup).map_err(|e| {
                Error::new("write", "Could not save the backup.").with_hint(e.to_string())
            })?;
            println!("Encrypted backup written. No password needs to be transferred.");
            Ok(())
        }
        BackupCommand::Restore { file, code, output } => {
            let bytes = std::fs::read_to_string(file).map_err(|e| {
                Error::new("read", "Could not read the backup.").with_hint(e.to_string())
            })?;
            let restored = restore_setup_code_backup(normalize_armor(&bytes)?.as_str(), code)?;
            std::fs::write(output, restored).map_err(|e| {
                Error::new("write", "Could not save the restored key.").with_hint(e.to_string())
            })?;
            println!("Key restored. Import it carefully and delete the temporary file.");
            Ok(())
        }
        BackupCommand::RestoreWithKey {
            file,
            output,
            password,
        } => {
            let bytes = std::fs::read_to_string(file).map_err(|e| {
                Error::new("read", "Could not read the backup.").with_hint(e.to_string())
            })?;
            let restored = restore_public_key_backup(
                normalize_armor(&bytes)?.as_str(),
                &identity.secret,
                password,
            )?;
            std::fs::write(output, restored).map_err(|e| {
                Error::new("write", "Could not save the restored key.").with_hint(e.to_string())
            })?;
            println!("Key restored.");
            Ok(())
        }
    }
}

// ---------------------------------------------------------------------------
// Native messaging bridge
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct BridgeRequest {
    id: String,
    method: String,
    params: Value,
}

async fn bridge(home: &Path) -> Result<()> {
    let mut stdin = std::io::stdin();
    let mut stdout = std::io::stdout();
    loop {
        let mut length_bytes = [0u8; 4];
        match stdin.read_exact(&mut length_bytes) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(()),
            Err(e) => {
                return Err(
                    Error::new("bridge_read", "Could not read a bridge request.")
                        .with_hint(e.to_string()),
                )
            }
        }
        let length = u32::from_le_bytes(length_bytes) as usize;
        if length == 0 || length > 8 * 1024 * 1024 {
            return Err(Error::new(
                "bridge_bad_request",
                "The bridge got an invalid message size.",
            ));
        }
        let mut payload = vec![0u8; length];
        stdin.read_exact(&mut payload).map_err(|e| {
            Error::new("bridge_read", "Could not read the bridge request.").with_hint(e.to_string())
        })?;

        let response = match serde_json::from_slice::<BridgeRequest>(&payload) {
            Ok(request) => handle_bridge(home, request).await,
            Err(e) => json!({
                "id": "unknown",
                "ok": false,
                "error": { "code": "BAD_REQUEST", "message": e.to_string() },
            }),
        };

        let bytes = serde_json::to_vec(&response)
            .map_err(|_| Error::new("bridge_write", "Could not encode a bridge response."))?;
        let len = (bytes.len() as u32).to_le_bytes();
        stdout
            .write_all(&len)
            .and_then(|_| stdout.write_all(&bytes))
            .and_then(|_| stdout.flush())
            .map_err(|e| {
                Error::new("bridge_write", "Could not write a bridge response.")
                    .with_hint(e.to_string())
            })?;
    }
}

fn ok(id: &str, result: Value) -> Value {
    json!({ "id": id, "ok": true, "result": result })
}

fn fail(id: &str, code: &str, message: &str) -> Value {
    json!({ "id": id, "ok": false, "error": { "code": code, "message": message } })
}

fn param<'a>(params: &'a Value, name: &str) -> &'a str {
    params.get(name).and_then(Value::as_str).unwrap_or("")
}

async fn handle_bridge(home: &Path, request: BridgeRequest) -> Value {
    let id = request.id.as_str();
    let params = &request.params;

    match request.method.as_str() {
        "ping" | "PING" => ok(id, json!({ "ok": true })),

        "generateKey" => {
            let email = param(params, "email");
            let name = {
                let n = param(params, "name");
                if n.is_empty() {
                    email.split('@').next().unwrap_or("NewPGP user").to_string()
                } else {
                    n.to_string()
                }
            };
            let password = param(params, "passphrase");
            match generate_identity(&name, email, password) {
                Ok(identity) => {
                    let saved = std::fs::write(
                        identity_path(home),
                        identity.secret_armor().unwrap_or_default(),
                    );
                    if saved.is_err() {
                        return fail(id, "UNKNOWN", "The key was created but could not be saved.");
                    }
                    let mut ring =
                        match load_keyring(home) {
                            Ok(r) => r,
                            Err(_) => return fail(
                                id,
                                "UNKNOWN",
                                "Your key was created but the contact store could not be opened.",
                            ),
                        };
                    let _ = ring.set_own_identity(&identity.public);
                    let _ = ring.save();
                    ok(
                        id,
                        json!({
                            "fingerprint": identity.fingerprint,
                            "publicKey": identity.public_armor().unwrap_or_default(),
                            "privateKey": identity.secret_armor().unwrap_or_default(),
                            "safetyNumber": newpgp_core::keys::safety_number(&identity.public, &identity.public),
                        }),
                    )
                }
                Err(e) => fail(id, "BAD_REQUEST", &e.friendly()),
            }
        }

        "encrypt" => {
            let identity = match load_identity(home) {
                Ok(i) => i,
                Err(_) => return fail(id, "NO_KEY", "Create your key first."),
            };
            let ring = match load_keyring(home) {
                Ok(r) => r,
                Err(_) => return fail(id, "UNKNOWN", "Contact store unavailable."),
            };
            let mut certs = Vec::new();
            for value in params
                .get("recipients")
                .and_then(Value::as_array)
                .map(|a| a.as_slice())
                .unwrap_or(&[])
            {
                let value = value.as_str().unwrap_or("");
                match resolve_recipient(value, &ring) {
                    Ok(cert) => certs.push(cert),
                    Err(_) => return fail(id, "NO_KEY", &format!("No key found for {value}")),
                }
            }
            let signer = if params.get("sign").and_then(Value::as_bool).unwrap_or(false) {
                Some((&identity.secret, ""))
            } else {
                None
            };
            match encrypt_text(
                param(params, "plaintext"),
                &certs,
                signer,
                EncryptionProfile::Compatible,
            ) {
                Ok(armored) => ok(
                    id,
                    json!({ "armored": armored, "fingerprint": identity.fingerprint }),
                ),
                Err(e) => fail(id, "DECRYPT_FAILED", &e.friendly()),
            }
        }

        "decrypt" => {
            let identity = match load_identity(home) {
                Ok(i) => i,
                Err(_) => return fail(id, "NO_KEY", "Create your key first."),
            };
            let ring = load_keyring(home)
                .unwrap_or_else(|_| KeyRing::open(Path::new("unused.json")).unwrap());
            let known: Vec<SignedPublicKey> = ring
                .contacts()
                .filter_map(|c| c.public_key().ok())
                .collect();
            match decrypt_bytes(
                param(params, "armored").as_bytes(),
                &identity.secret,
                "",
                &known,
            ) {
                Ok(result) => {
                    let plaintext = String::from_utf8_lossy(&result.data).to_string();
                    let (status, signer_fp) = match &result.signature {
                        SignatureStatus::Verified(fp) => ("verified", Some(fp.clone())),
                        SignatureStatus::Invalid => ("invalid", None),
                        _ => ("unverified", None),
                    };
                    ok(
                        id,
                        json!({ "plaintext": plaintext, "signature": status, "signerFingerprint": signer_fp }),
                    )
                }
                Err(e) => fail(id, "DECRYPT_FAILED", &e.friendly()),
            }
        }

        "sign" => {
            let identity = match load_identity(home) {
                Ok(i) => i,
                Err(_) => return fail(id, "NO_KEY", "Create your key first."),
            };
            let data = param(params, "data").as_bytes();
            let signature = DetachedSignature::sign_binary_data(
                OsRng,
                &identity.secret.primary_key,
                &Password::empty(),
                HashAlgorithm::Sha256,
                data,
            );
            match signature {
                Ok(sig) => match sig.to_armored_string(Default::default()) {
                    Ok(armored) => ok(id, json!({ "armored": armored })),
                    Err(e) => fail(id, "UNKNOWN", &e.to_string()),
                },
                Err(e) => fail(id, "UNKNOWN", &e.to_string()),
            }
        }

        "verify" => {
            let data = param(params, "data").as_bytes();
            let armor = match normalize_armor(param(params, "signature")) {
                Ok(a) => a,
                Err(e) => return fail(id, "INVALID_ARMOR", &e.friendly()),
            };
            let sig = DetachedSignature::from_armor_single(std::io::Cursor::new(armor.as_bytes()));
            let sig = match sig {
                Ok((s, _)) => s,
                Err(e) => return fail(id, "INVALID_ARMOR", &e.to_string()),
            };
            let ring = load_keyring(home)
                .unwrap_or_else(|_| KeyRing::open(Path::new("unused.json")).unwrap());
            for contact in ring.contacts() {
                if let Ok(cert) = contact.public_key() {
                    if sig.verify(&cert, data).is_ok() {
                        return ok(
                            id,
                            json!({ "valid": true, "signerFingerprint": contact.fingerprint }),
                        );
                    }
                }
            }
            ok(id, json!({ "valid": false }))
        }

        "getKey" => {
            let ring = match load_keyring(home) {
                Ok(r) => r,
                Err(_) => return fail(id, "UNKNOWN", "Contact store unavailable."),
            };
            let email = param(params, "email");
            let contact = if email.is_empty() {
                ring.contacts().next()
            } else {
                ring.get(email)
            };
            match contact {
                Some(c) => ok(
                    id,
                    json!({
                        "fingerprint": c.fingerprint,
                        "email": c.email,
                        "safetyNumber": ring.safety_number_for(&c.email).unwrap_or_default(),
                    }),
                ),
                None => fail(id, "NO_KEY", "No key found."),
            }
        }

        "discoverKey" => {
            let email = param(params, "email");
            match discover(email, &HttpFetcher::new()).await {
                Ok(found) => {
                    let mut ring = load_keyring(home)
                        .unwrap_or_else(|_| KeyRing::open(Path::new("unused.json")).unwrap());
                    let _ = ring.learn(email, &found.cert, found.source);
                    let _ = ring.save();
                    ok(
                        id,
                        json!({
                            "email": email,
                            "fingerprint": found.fingerprint,
                            "safetyNumber": ring.safety_number_for(email).unwrap_or_default(),
                            "source": match found.source {
                                KeySource::KeysOpenpgpOrg => "keys.openpgp.org",
                                KeySource::Autocrypt => "Autocrypt",
                                _ => "WKD",
                            },
                            "confidence": "medium",
                        }),
                    )
                }
                Err(e) => fail(id, "NO_KEY", &e.friendly()),
            }
        }

        other => fail(
            id,
            "UNSUPPORTED",
            &format!("Unknown bridge method: {other}"),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recipient_resolution_accepts_armor() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let ring = KeyRing::open(Path::new("nonexistent.json")).unwrap();
        let cert = resolve_recipient(&alice.public_armor().unwrap(), &ring).unwrap();
        assert_eq!(raw_fingerprint(&cert), alice.fingerprint.replace(' ', ""));
    }

    #[test]
    fn error_messages_are_friendly() {
        let e = Error::new("x", "Something went wrong.").with_hint("Try again.");
        assert_eq!(e.friendly(), "Something went wrong. Try again.");
    }
}
