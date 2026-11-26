// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! NewPGP command line app and native messaging bridge.
//!
//! The bridge speaks Chrome's 4-byte little-endian native messaging protocol
//! on stdin/stdout. All diagnostics go to stderr, never stdout.

use std::io::{IsTerminal, Read, Write};
use std::path::{Path, PathBuf};

use clap::{Parser, Subcommand};
use newpgp_backup::{
    create_public_key_backup, create_setup_code_backup, generate_setup_code,
    restore_public_key_backup, restore_setup_code_backup,
};
use newpgp_core::armor::normalize_armor;
use newpgp_core::files::{decrypt_file, encrypt_file};
use newpgp_core::keys::{generate_identity, parse_public_key, parse_secret_key, Identity};
use newpgp_core::message::{decrypt_bytes, encrypt_text, EncryptionProfile, SignatureStatus};
use newpgp_core::{Error, Result};
use newpgp_discovery::invite::Invite;
use newpgp_discovery::{discover, HttpFetcher};
use newpgp_keyring::{Contact, KeyRing, KeySource, TofuOutcome};
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
    long_about = "Friendly OpenPGP for everyone.\n\nStart with `newpgp setup`: one command and three plain-language questions. If something looks wrong, run `newpgp doctor`; it only reads files and never shows a secret.\n\nAll keys and settings live in one folder. `newpgp doctor` prints where that folder is.",
    after_help = "START HERE\n    newpgp setup                        Create your key and offer an emergency backup\n    newpgp doctor                       Check that everything is ready\n\nEVERYDAY USE\n    newpgp key show                     Show your public key and fingerprint\n    newpgp compose --body \"Hello\" --to friend@example.com\n\nSECRETS\n    Prefer NEWPGP_KEY_PASSWORD over --password so your password never\n    appears in shell history or the process list.",
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
    /// Set up your key in one friendly step. Start here if you're new.
    ///
    /// Asks three plain-language questions, creates your key, and offers to
    /// make an emergency backup. Everything is stored in NEWPGP_HOME.
    #[command(
        long_about = "Create your key in one friendly step.\n\nRun `newpgp setup` and answer three questions. To automate it, pass --name, --email and --password, or set NEWPGP_KEY_PASSWORD (safer: it keeps the secret out of shell history).\n\nA password of at least 12 characters is recommended. Shorter passwords get a gentle warning but are still allowed. Press Enter to skip the password; we then make a backup code instead.\n\nBy default you are asked whether to make an emergency backup. --no-backup skips the question; --backup FILE creates one without asking."
    )]
    Setup {
        /// The name people will see on your key.
        #[arg(long)]
        name: Option<String>,
        /// Your email address, used to find your key automatically.
        #[arg(long)]
        email: Option<String>,
        /// A password only you know. Omit to be asked, or set
        /// NEWPGP_KEY_PASSWORD to keep it out of shell history.
        #[arg(long, env = "NEWPGP_KEY_PASSWORD")]
        password: Option<String>,
        /// Skip the "make an emergency backup?" question.
        #[arg(long)]
        no_backup: bool,
        /// Create an Argon2-protected emergency backup at this file, without asking.
        #[arg(long, value_name = "FILE")]
        backup: Option<PathBuf>,
    },
    /// Check that everything is ready. Read-only: changes nothing.
    ///
    /// Prints plain-language PASS/FAIL lines and exactly what to do next.
    #[command(
        visible_alias = "check",
        long_about = "Check that NewPGP is ready to use.\n\nThis only reads your files: it never creates, changes or prints any secret. It shows what works, what doesn't, the app version and where your data lives."
    )]
    Doctor,
    /// Create your key pair with flags. New users should prefer `setup`.
    Init {
        #[arg(long)]
        name: String,
        #[arg(long)]
        email: String,
        /// Password for the key. Leave empty for no password.
        /// Prefer the NEWPGP_KEY_PASSWORD environment variable so the secret
        /// never appears in shell history or the process list.
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
        /// Password for your key. Only needed when --sign uses a protected
        /// key; unprotected keys need no password. Prefer the
        /// NEWPGP_KEY_PASSWORD environment variable so the secret never
        /// appears in shell history or the process list.
        #[arg(long, env = "NEWPGP_KEY_PASSWORD", default_value = "")]
        password: String,
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
        /// Password for your key. Compose always signs, so a protected key
        /// needs this to unlock signing; unprotected keys need no password.
        /// Prefer the NEWPGP_KEY_PASSWORD environment variable so the secret
        /// never appears in shell history or the process list.
        #[arg(long, env = "NEWPGP_KEY_PASSWORD", default_value = "")]
        password: String,
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
    /// Accept a pending key change after comparing the safety number.
    ConfirmChange { email: String },
    /// Keep the old key and discard a pending key change.
    RejectChange { email: String },
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
        output: PathBuf,
        /// The 36-digit setup code. Use NEWPGP_SETUP_CODE to keep it off the
        /// command line and out of shell history.
        #[arg(long, env = "NEWPGP_SETUP_CODE")]
        code: String,
    },
    /// Restore a public-key backup.
    RestoreWithKey {
        file: PathBuf,
        output: PathBuf,
        /// Password protecting the key used to decrypt the backup. Use
        /// NEWPGP_KEY_PASSWORD to keep it off the command line.
        #[arg(long, env = "NEWPGP_KEY_PASSWORD")]
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
        fingerprint: newpgp_core::keys::format_fingerprint(&secret.primary_key),
        secret,
        public,
    })
}

fn load_keyring(home: &Path) -> Result<KeyRing> {
    KeyRing::open(&keyring_path(home))
}

// ---------------------------------------------------------------------------
// Guided setup and read-only health check
// ---------------------------------------------------------------------------

const SETUP_NAME_QUESTION: &str = "What should people see when they get your key? (your name): ";
const SETUP_EMAIL_QUESTION: &str =
    "Which email do you use? (we use this to find your key automatically): ";
const SETUP_PASSWORD_QUESTION: &str =
    "Choose a password only you know (press Enter to skip — we'll make a backup code instead): ";

/// Ask one question, flushing the prompt before blocking on input.
/// `None` means end of input (for example, a scripted run with no stdin).
fn ask(question: &str) -> Option<String> {
    print!("{question}");
    let _ = std::io::stdout().flush();
    let mut line = String::new();
    match std::io::stdin().read_line(&mut line) {
        Ok(0) => None,
        Ok(_) => Some(line.trim().to_string()),
        Err(_) => None,
    }
}

/// An address needs something on both sides of a single `@` and no spaces.
fn valid_email(value: &str) -> bool {
    match value.split_once('@') {
        Some((local, domain)) => {
            !local.trim().is_empty()
                && !domain.trim().is_empty()
                && !value.chars().any(char::is_whitespace)
        }
        None => false,
    }
}

/// The one-command guided flow. Missing answers come from stdin; flags (or
/// NEWPGP_KEY_PASSWORD) make the same flow usable from scripts.
fn setup_command(
    home: &Path,
    name: Option<String>,
    email: Option<String>,
    password: Option<String>,
    no_backup: bool,
    backup: Option<PathBuf>,
) -> Result<()> {
    println!("Welcome! This takes about a minute.");
    println!("I'll make a key so you can send and receive private messages.");
    println!();

    let name = match name {
        Some(value) => {
            let value = value.trim().to_string();
            if value.is_empty() {
                return Err(Error::new(
                    "setup_name",
                    "Please tell me the name people should see on your key.",
                )
                .with_hint("Run `newpgp setup --name \"Your Name\" --email you@example.com`."));
            }
            value
        }
        None => loop {
            match ask(SETUP_NAME_QUESTION) {
                Some(value) if !value.is_empty() => break value,
                Some(_) => println!("Please type a name so people know whose key this is."),
                None => {
                    return Err(Error::new(
                        "setup_input",
                        "I couldn't read your answer for the name.",
                    )
                    .with_hint("Run `newpgp setup --name \"Your Name\" --email you@example.com`."))
                }
            }
        },
    };

    let email = match email {
        Some(value) => {
            let value = value.trim().to_string();
            if !valid_email(&value) {
                return Err(
                    Error::new("setup_email", "That email address doesn't look right.").with_hint(
                        "Use the form name@example.com so people can find your key automatically.",
                    ),
                );
            }
            value
        }
        None => loop {
            match ask(SETUP_EMAIL_QUESTION) {
                Some(value) if valid_email(&value) => break value,
                Some(_) => {
                    println!("That doesn't look like an email address. Try name@example.com.")
                }
                None => {
                    return Err(Error::new(
                        "setup_input",
                        "I couldn't read your answer for the email.",
                    )
                    .with_hint("Run `newpgp setup --email you@example.com`."))
                }
            }
        },
    };

    println!("Your password locks your key so only you can use it; if you forget it, nobody can get it back.");
    let password = match password {
        Some(value) => value,
        None => ask(SETUP_PASSWORD_QUESTION).unwrap_or_default(),
    };
    if !password.is_empty() && password.chars().count() < 12 {
        println!("Heads up: that password is short. Twelve characters or more is much safer.");
        println!("You can keep it, but a longer one is harder for someone else to guess.");
    }
    if !password.is_empty() {
        println!("Tip: NEWPGP_KEY_PASSWORD keeps your password out of shell history next time.");
    }

    println!();
    println!("Creating your key... (this usually takes a second)");
    std::fs::create_dir_all(home).map_err(|e| {
        Error::new("home", "Could not create the data folder.").with_hint(e.to_string())
    })?;
    let identity = generate_identity(&name, &email, &password)?;
    std::fs::write(identity_path(home), identity.secret_armor()?).map_err(|e| {
        Error::new("save_identity", "Could not save your key.").with_hint(e.to_string())
    })?;
    let mut ring = load_keyring(home)?;
    ring.set_own_identity(&identity.public)?;
    // Also remember the key under its owner's address so encrypting to
    // yourself and bridge lookups work immediately, as in `bridge generateKey`.
    let _ = ring.learn(&email, &identity.public, KeySource::Manual);
    ring.save()?;
    // Make the local search key now so a fresh setup is completely healthy.
    let _ = load_index_key(home)?;

    let backup_path = if no_backup {
        None
    } else if let Some(path) = backup {
        Some(path)
    } else if std::io::stdin().is_terminal() {
        let answer = ask("Would you like to make an emergency backup now? (yes/no) [yes]: ")
            .unwrap_or_default()
            .to_ascii_lowercase();
        if answer.is_empty() || answer.starts_with('y') {
            Some(home.join("emergency-backup.asc"))
        } else {
            println!("No problem. Make one later with `newpgp backup create <file>`.");
            None
        }
    } else {
        println!("\nSkipping the backup question because this isn't interactive.");
        println!("Make one when you're ready: newpgp backup create <file>");
        None
    };
    let made_backup = backup_path.is_some();
    if let Some(path) = backup_path {
        println!();
        println!("Making your emergency backup...");
        backup_command(
            home,
            &BackupCommand::Create {
                output: path.clone(),
            },
        )?;
        println!("Backup saved at {}.", path.display());
    }

    println!();
    println!("Quick check...");
    let failures = doctor_checks(home);
    if failures > 0 {
        return Err(Error::new(
            "setup_check",
            "Your key was created, but a final check didn't pass.",
        )
        .with_hint("Run `newpgp doctor` to see what needs attention."));
    }

    println!();
    println!("You're ready!");
    println!("Your fingerprint: {}", identity.fingerprint);
    println!("Share it so people can check they have the right key.");
    println!();
    println!("Send your first private message:");
    println!("    newpgp compose --body \"Hello\" --to friend@example.com");
    println!("See your public key (safe to share):");
    println!("    newpgp key show");
    if password.is_empty() && !made_backup {
        println!();
        println!("Tip: make a backup so you can recover this key if you lose this device:");
        println!("    newpgp backup create <file>");
    }
    Ok(())
}

/// Read-only health checks. Returns the number of hard failures. Never prints
/// passwords, setup codes, secret keys or the local search key.
fn doctor_checks(home: &Path) -> usize {
    let mut failures = 0;

    match load_identity(home) {
        Ok(identity) => {
            println!("PASS: Your key is here and readable.");
            println!("      Fingerprint: {}", identity.fingerprint);
        }
        Err(_) => {
            failures += 1;
            println!("FAIL: Your key is missing or could not be read.");
            println!("      NEXT: run `newpgp setup` to create one.");
        }
    }

    match load_keyring(home) {
        Ok(ring) => {
            println!("PASS: Your contact list is readable.");
            println!("      Contacts saved: {}", ring.contacts().count());
        }
        Err(_) => {
            failures += 1;
            println!("FAIL: Your contact list could not be read.");
            println!(
                "      NEXT: restore a backup or add contacts again with `newpgp key import`."
            );
        }
    }

    let index_key = home.join("index-key.bin");
    match std::fs::read(&index_key) {
        Ok(bytes) if bytes.len() == 32 => println!("PASS: Local search is ready."),
        Ok(_) => {
            failures += 1;
            println!("FAIL: The local search key is damaged.");
            println!(
                "      NEXT: delete {} and run `newpgp search hello` once.",
                index_key.display()
            );
        }
        Err(_) => println!(
            "NOTE: Local search hasn't made its key yet; it appears the first time you use `newpgp search`. Nothing to do."
        ),
    }

    println!("NOTE: Your keys and messages use standard OpenPGP, so GPG can read them. The newest features may need a recent GPG version.");
    println!("Version: NewPGP {}", env!("CARGO_PKG_VERSION"));
    println!("Data folder: {}", home.display());
    failures
}

/// Resolve a recipient string to a certificate.
fn resolve_recipient(value: &str, ring: &KeyRing) -> Result<SignedPublicKey> {
    if value.trim_start().starts_with("-----BEGIN PGP ") {
        return parse_public_key(value.as_bytes());
    }
    if let Some(contact) = ring.get(value) {
        return contact.public_key();
    }
    // Help text advertises fingerprints, so accept them as recipients too.
    // Spaces and case are insignificant in a fingerprint.
    let wanted = value.replace(' ', "");
    if let Some(contact) = ring
        .contacts()
        .find(|c| c.fingerprint.replace(' ', "").eq_ignore_ascii_case(&wanted))
    {
        return contact.public_key();
    }
    Err(
        Error::new("unknown_recipient", format!("No key found for '{value}'."))
            .with_hint("Use an email address, a fingerprint, or an armored public key."),
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
        Command::Setup {
            name,
            email,
            password,
            no_backup,
            backup,
        } => setup_command(
            home,
            name.clone(),
            email.clone(),
            password.clone(),
            *no_backup,
            backup.clone(),
        ),
        Command::Doctor => {
            println!("Checking NewPGP (nothing will be changed)...\n");
            let failures = doctor_checks(home);
            if failures == 0 {
                println!("\nAll good — you're ready to use NewPGP.");
                Ok(())
            } else {
                let message = if !identity_path(home).exists() {
                    "You haven't created a key yet. Run `newpgp setup` to create one."
                } else {
                    "Some checks didn't pass. Follow the NEXT lines above, then run `newpgp doctor` again."
                };
                Err(Error::new("doctor", message))
            }
        }
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
            password,
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
                Some((&identity.secret, password.as_str()))
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
            password,
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
                signer: Some((&identity.secret, password.as_str())),
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
                TofuOutcome::KeyChanged { previous, current } => {
                    // Saving records the *pending* change; the keyring keeps the
                    // old key active until the user explicitly confirms it.
                    print_key_change_warning(&ring, email, &previous, &current);
                }
                _ => {
                    println!("Found and saved a key for {email} via {}.", found.url);
                    println!("Fingerprint: {}", found.fingerprint);
                    println!("Safety number: {}", ring.safety_number_for(email)?);
                }
            }
            Ok(())
        }
        KeyCommand::Import { email, file } => {
            let bytes = std::fs::read(file).map_err(|e| {
                Error::new("read", "Could not read that key file.").with_hint(e.to_string())
            })?;
            let cert = parse_public_key(&bytes)?;
            let mut ring = load_keyring(home)?;
            let outcome = ring.learn(email, &cert, KeySource::Manual)?;
            ring.save()?;
            match outcome {
                TofuOutcome::KeyChanged { previous, current } => {
                    // The old key stays active; only the pending change is saved.
                    print_key_change_warning(&ring, email, &previous, &current);
                }
                _ => println!(
                    "Saved key for {email}. Safety number: {}",
                    ring.safety_number_for(email)?
                ),
            }
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
        KeyCommand::ConfirmChange { email } => {
            let mut ring = load_keyring(home)?;
            let new_fingerprint = ring
                .pending_change(email)
                .map(|c| c.pretty_fingerprint())
                .unwrap_or_default();
            ring.confirm_change(email)?;
            ring.save()?;
            println!(
                "Confirmed. The new key for {email} is now active. Fingerprint: {new_fingerprint}"
            );
            println!("Safety number: {}", ring.safety_number_for(email)?);
            Ok(())
        }
        KeyCommand::RejectChange { email } => {
            let mut ring = load_keyring(home)?;
            ring.reject_change(email)?;
            ring.save()?;
            println!("Rejected. Your existing key for {email} is unchanged.");
            Ok(())
        }
    }
}

/// Loud, plain-language warning shown when a contact's key changes.
///
/// The new key is deliberately *not* activated here: the user must compare a
/// safety number out of band and then run `key confirm-change` (or reject it).
fn print_key_change_warning(ring: &KeyRing, email: &str, previous: &Contact, current: &Contact) {
    let safety = |contact: &Contact| -> String {
        match ring.own_identity().and_then(|me| me.public_key().ok()) {
            Some(me) => match contact.public_key() {
                Ok(key) => newpgp_core::keys::safety_number(&me, &key),
                Err(_) => newpgp_keyring::short_code(&contact.fingerprint),
            },
            None => newpgp_keyring::short_code(&contact.fingerprint),
        }
    };

    println!();
    println!("*** WARNING: THE KEY FOR {email} HAS CHANGED ***");
    println!("The key I just found is different from the one saved for {email}.");
    println!("That can be a normal new key, or it can be someone pretending to be {email}.");
    println!("I have NOT replaced the saved key. The old key is still the one in use.");
    println!("Old key fingerprint: {}", previous.pretty_fingerprint());
    println!("New key fingerprint: {}", current.pretty_fingerprint());
    println!("Old safety number: {}", safety(previous));
    println!("New safety number: {}", safety(current));
    println!();
    println!("Call {email} on a number you already trust (or meet in person) and compare");
    println!("the safety number. Do not compare it over the same email you are worried about.");
    println!("If the new key is right, run:");
    println!("    newpgp key confirm-change {email}");
    println!("If the new key is wrong, run:");
    println!("    newpgp key reject-change {email}");
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

/// A key password supplied by a bridge caller. `password` is accepted as a
/// friendly alias for older callers, but `passphrase` is the documented field.
/// This is only used when a caller explicitly provides it: the browser
/// extension must never send the key password on its own.
fn bridge_passphrase(params: &Value) -> &str {
    let value = param(params, "passphrase");
    if value.is_empty() {
        param(params, "password")
    } else {
        value
    }
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
                    // Also store it under the owner's email so `getKey` and
                    // other contact lookups work immediately after creation.
                    let _ = ring.learn(email, &identity.public, KeySource::Manual);
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
            // Signing a protected key needs its password, but only callers
            // that explicitly supply one can unlock it. The browser extension
            // never sends the password automatically.
            let signer = if params.get("sign").and_then(Value::as_bool).unwrap_or(false) {
                Some((&identity.secret, bridge_passphrase(params)))
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
            let key_password = bridge_passphrase(params);
            // A generated key can have an unprotected encryption subkey even
            // when its primary key is locked. rPGP would then decrypt without
            // ever checking the password, so check it explicitly first and
            // honour what the caller told us about the key.
            if identity
                .secret
                .primary_key
                .unlock(&Password::from(key_password), |_, _| Ok(()))
                .is_err()
            {
                return fail(
                    id,
                    "DECRYPT_FAILED",
                    "That key is locked with a different password. Try the password you chose when you created your key.",
                );
            }
            let known: Vec<SignedPublicKey> = ring
                .contacts()
                .filter_map(|c| c.public_key().ok())
                .collect();
            match decrypt_bytes(
                param(params, "armored").as_bytes(),
                &identity.secret,
                key_password,
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
                &Password::from(bridge_passphrase(params)),
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
                // With no address requested, return our own identity rather
                // than an arbitrary contact from the BTreeMap.
                ring.own_identity()
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
    use newpgp_core::keys::raw_fingerprint;

    #[test]
    fn recipient_resolution_accepts_armor() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let ring = KeyRing::open(Path::new("nonexistent.json")).unwrap();
        let cert = resolve_recipient(&alice.public_armor().unwrap(), &ring).unwrap();
        assert_eq!(raw_fingerprint(&cert), alice.fingerprint.replace(' ', ""));
    }

    #[test]
    fn recipient_resolution_accepts_fingerprints() {
        let alice = generate_identity("Alice", "alice@example.com", "").unwrap();
        let mut ring = KeyRing::open(Path::new("nonexistent.json")).unwrap();
        ring.learn("alice@example.com", &alice.public, KeySource::Manual)
            .unwrap();

        let raw = alice.fingerprint.replace(' ', "");
        let lower = raw.to_ascii_lowercase();
        let spaced = raw
            .as_bytes()
            .chunks(4)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect::<Vec<_>>()
            .join(" ");

        for value in [raw.as_str(), lower.as_str(), spaced.as_str()] {
            let cert = resolve_recipient(value, &ring)
                .unwrap_or_else(|e| panic!("fingerprint {value} was not resolved: {e:?}"));
            assert_eq!(raw_fingerprint(&cert), raw);
        }
    }

    #[test]
    fn error_messages_are_friendly() {
        let e = Error::new("x", "Something went wrong.").with_hint("Try again.");
        assert_eq!(e.friendly(), "Something went wrong. Try again.");
    }
}
