// SPDX-License-Identifier: GPL-3.0-or-later
//! Real end-to-end tests: every check here launches the compiled `newpgp`
//! binary as a subprocess, with its own isolated `NEWPGP_HOME`.
//!
//! No dev-dependencies are used: `tempfile` is not declared for this package,
//! so each test creates a uniquely named directory under `std::env::temp_dir()`
//! and removes it with a `Drop` guard.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};

static SEQ: AtomicU64 = AtomicU64::new(0);

/// An isolated NEWPGP_HOME that is deleted when the test finishes.
struct Home {
    path: PathBuf,
}

impl Home {
    fn new(label: &str) -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock is set")
            .as_nanos();
        let seq = SEQ.fetch_add(1, Ordering::Relaxed);
        let base = std::env::temp_dir();
        for attempt in 0..100u32 {
            let name = format!(
                "newpgp-e2e-{}-{}-{nanos}-{seq}-{attempt}",
                label,
                std::process::id()
            );
            let path = base.join(name);
            match fs::create_dir(&path) {
                Ok(()) => return Self { path },
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => panic!("cannot create {}: {e}", path.display()),
            }
        }
        panic!("could not allocate a unique temp home for {label}");
    }

    fn file(&self, name: &str) -> PathBuf {
        self.path.join(name)
    }

    fn command(&self, args: &[&str]) -> Command {
        let mut cmd = Command::new(env!("CARGO_BIN_EXE_newpgp"));
        cmd.env("NEWPGP_HOME", &self.path);
        // Never inherit a password from the caller's environment.
        cmd.env_remove("NEWPGP_KEY_PASSWORD");
        cmd.args(args);
        cmd
    }

    fn run(&self, args: &[&str]) -> Output {
        self.command(args).output().expect("could not run newpgp")
    }

    fn ok(&self, args: &[&str]) -> String {
        let out = self.run(args);
        assert!(
            out.status.success(),
            "newpgp {} failed (exit {:?}):\nstdout: {}\nstderr: {}",
            args.join(" "),
            out.status.code(),
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr),
        );
        String::from_utf8(out.stdout).expect("newpgp stdout is UTF-8")
    }
}

impl Drop for Home {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

fn init(home: &Home, name: &str, email: &str) -> String {
    let out = home.ok(&["init", "--name", name, "--email", email]);
    assert!(
        home.file("identity.asc").exists(),
        "init must write identity.asc into NEWPGP_HOME"
    );
    out
}

fn fingerprint(out: &str) -> String {
    let line = out
        .lines()
        .find(|line| line.trim_start().starts_with("Fingerprint: "))
        .unwrap_or_else(|| panic!("no fingerprint printed in:\n{out}"));
    let fp = line.trim_start()["Fingerprint: ".len()..]
        .trim()
        .to_string();
    let raw = raw_fingerprint(&fp);
    assert_eq!(raw.len(), 40, "fingerprint should be 40 hex chars: {fp}");
    assert!(
        raw.chars().all(|c| c.is_ascii_hexdigit()),
        "fingerprint should be hex: {fp}"
    );
    raw
}

/// The CLI prints fingerprints in spaced groups of four; links and keyrings
/// use the unspaced form.
fn raw_fingerprint(fp: &str) -> String {
    fp.replace(' ', "")
}

fn armor_block(text: &str, label: &str) -> String {
    let begin = format!("-----BEGIN PGP {label}-----");
    let end = format!("-----END PGP {label}-----");
    let start = text
        .find(&begin)
        .unwrap_or_else(|| panic!("missing {begin} in:\n{text}"));
    let rest = &text[start..];
    let stop = rest
        .find(&end)
        .unwrap_or_else(|| panic!("missing {end} in:\n{text}"))
        + end.len();
    rest[..stop].to_string()
}

/// Export a real public key through the CLI, the way a user would share it.
fn export_public_key(home: &Home, path: &Path) {
    let show = home.ok(&["key", "show"]);
    fs::write(path, armor_block(&show, "PUBLIC KEY BLOCK")).expect("write public key file");
}

fn setup_code(text: &str) -> String {
    for token in text.split_whitespace() {
        let groups: Vec<&str> = token.split('-').collect();
        if groups.len() == 9
            && groups
                .iter()
                .all(|g| g.len() == 4 && g.bytes().all(|b| b.is_ascii_digit()))
        {
            return token.to_string();
        }
    }
    panic!("no 9x4-digit setup code printed in:\n{text}");
}

fn write_frame(stream: &mut impl Write, request: &Value) {
    let payload = serde_json::to_vec(request).expect("serialize bridge request");
    let length = (payload.len() as u32).to_le_bytes();
    stream.write_all(&length).expect("write frame length");
    stream.write_all(&payload).expect("write frame payload");
    stream.flush().expect("flush frame");
}

/// The CLI writes raw OpenPGP packets; the bridge takes armored text. Wrap
/// the packets in armor exactly as an email client would when pasting them.
fn armor_binary(binary: &[u8]) -> String {
    struct Raw(Vec<u8>);
    impl pgp::ser::Serialize for Raw {
        fn to_writer<W: Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
            writer.write_all(&self.0)?;
            Ok(())
        }
        fn write_len(&self) -> usize {
            self.0.len()
        }
    }
    let mut out = Vec::new();
    pgp::armor::write(
        &Raw(binary.to_vec()),
        pgp::armor::BlockType::Message,
        &mut out,
        None,
        true,
    )
    .expect("armor the CLI ciphertext");
    String::from_utf8(out).expect("armor is UTF-8")
}

fn parse_frames(bytes: &[u8]) -> Vec<Value> {
    let mut pos = 0;
    let mut frames = Vec::new();
    while pos < bytes.len() {
        assert!(
            pos + 4 <= bytes.len(),
            "truncated bridge length prefix at {pos}"
        );
        let len = u32::from_le_bytes(bytes[pos..pos + 4].try_into().unwrap()) as usize;
        pos += 4;
        assert!(
            pos + len <= bytes.len(),
            "truncated bridge payload at {pos} (need {len})"
        );
        frames
            .push(serde_json::from_slice(&bytes[pos..pos + len]).expect("bridge payload is JSON"));
        pos += len;
    }
    frames
}

/// Wait for a child, killing it if the native host fails to exit on EOF.
fn finish(mut child: std::process::Child) -> Output {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        match child.try_wait().expect("try_wait") {
            Some(_) => break,
            None if Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(50));
            }
            None => {
                child.kill().expect("kill hung bridge");
                break;
            }
        }
    }
    child.wait_with_output().expect("collect bridge output")
}

#[test]
fn init_creates_identity_and_prints_fingerprint() {
    let home = Home::new("init");
    let out = init(&home, "Alice Example", "alice@example.com");

    assert!(out.contains("Your key is ready."), "output: {out}");
    let fp = fingerprint(&out);
    let saved = fs::read_to_string(home.file("identity.asc")).expect("read identity.asc");
    assert!(
        saved.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----"),
        "identity.asc should hold the secret key"
    );
    let show = home.ok(&["key", "show"]);
    assert_eq!(
        fingerprint(&show),
        fp,
        "the printed fingerprint should belong to the saved key"
    );
    assert!(home.file("contacts.json").exists());
}

#[test]
fn key_show_and_list_work_with_a_real_imported_key() {
    let alice = Home::new("key-show-alice");
    let bob = Home::new("key-show-bob");
    let alice_out = init(&alice, "Alice Example", "alice@example.com");
    let bob_out = init(&bob, "Bob Example", "bob@example.com");
    let bob_fp = fingerprint(&bob_out);

    let show = alice.ok(&["key", "show"]);
    assert_eq!(fingerprint(&show), fingerprint(&alice_out));
    assert!(
        show.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"),
        "key show should print the public key: {show}"
    );

    let bob_key = alice.file("bob.asc");
    export_public_key(&bob, &bob_key);
    let imported = alice.ok(&[
        "key",
        "import",
        "bob@example.com",
        bob_key.to_str().unwrap(),
    ]);
    assert!(imported.contains("Safety number:"), "output: {imported}");

    let list = alice.ok(&["key", "list"]);
    assert!(
        list.contains("bob@example.com") && list.replace(' ', "").contains(&bob_fp),
        "key list should show the imported contact: {list}"
    );
}

#[test]
fn key_invite_produces_an_https_link_with_a_fragment() {
    let home = Home::new("invite");
    let out = init(&home, "Alice Example", "alice@example.com");
    let fp = fingerprint(&out);

    let invite = home.ok(&["key", "invite", "friend@example.com"]);
    let url = invite.trim();
    assert!(url.starts_with("https://"), "invite must be https: {url}");
    let (base, fragment) = url.split_once('#').expect("invite has a # fragment");
    assert!(
        !base.contains('?'),
        "payload must be in the fragment: {url}"
    );
    assert!(
        fragment.contains("addr=friend%40example.com")
            && fragment.contains("fpr=")
            && fragment.contains("key="),
        "fragment should carry address, fingerprint and key: {fragment}"
    );
    assert!(
        fragment.contains(&fp),
        "fragment should carry our fingerprint: {fragment}"
    );
}

#[test]
fn compose_both_and_pgp_only_modes_behave_differently() {
    let alice = Home::new("compose-alice");
    let bob = Home::new("compose-bob");
    init(&alice, "Alice Example", "alice@example.com");
    init(&bob, "Bob Example", "bob@example.com");
    let bob_key = alice.file("bob.asc");
    export_public_key(&bob, &bob_key);
    alice.ok(&[
        "key",
        "import",
        "bob@example.com",
        bob_key.to_str().unwrap(),
    ]);

    let plaintext = "Lunch at noon tomorrow";
    let both = alice.ok(&[
        "compose",
        "--body",
        plaintext,
        "-r",
        "bob@example.com",
        "--mode",
        "both",
    ]);
    assert!(
        both.contains(plaintext),
        "send-both keeps readable text: {both}"
    );
    assert!(
        both.contains("-----BEGIN PGP MESSAGE-----"),
        "send-both appends an encrypted block: {both}"
    );

    let pgp_only = alice.ok(&[
        "compose",
        "--body",
        plaintext,
        "-r",
        "bob@example.com",
        "--mode",
        "pgp-only",
    ]);
    assert!(
        !pgp_only.contains(plaintext),
        "pgp-only must not leak the plaintext: {pgp_only}"
    );
    assert!(
        pgp_only.contains("-----BEGIN PGP MESSAGE-----"),
        "pgp-only still contains the ciphertext: {pgp_only}"
    );

    let plain = alice.ok(&[
        "compose",
        "--body",
        plaintext,
        "-r",
        "bob@example.com",
        "--mode",
        "plain",
    ]);
    assert_eq!(plain.trim(), plaintext);
    assert!(!plain.contains("-----BEGIN PGP MESSAGE-----"));
}

#[test]
fn encrypt_then_decrypt_roundtrips_a_binary_file_between_two_homes() {
    let alice = Home::new("file-alice");
    let bob = Home::new("file-bob");
    init(&alice, "Alice Example", "alice@example.com");
    init(&bob, "Bob Example", "bob@example.com");

    // Exchange public keys exactly as two users would: export, then import.
    let alice_key = bob.file("alice.asc");
    let bob_key = alice.file("bob.asc");
    export_public_key(&alice, &alice_key);
    export_public_key(&bob, &bob_key);
    alice.ok(&[
        "key",
        "import",
        "bob@example.com",
        bob_key.to_str().unwrap(),
    ]);
    bob.ok(&[
        "key",
        "import",
        "alice@example.com",
        alice_key.to_str().unwrap(),
    ]);

    // Deliberately non-text bytes (NULs, invalid UTF-8, all byte values).
    let payload: Vec<u8> = (0..=255u8).chain(0..=255u8).collect();
    let input = alice.file("payload.bin");
    let encrypted = alice.file("payload.bin.pgp");
    let decrypted = bob.file("payload.decrypted.bin");
    fs::write(&input, &payload).expect("write binary payload");

    let enc_out = alice.ok(&[
        "encrypt",
        "-r",
        "bob@example.com",
        "-f",
        input.to_str().unwrap(),
        "-o",
        encrypted.to_str().unwrap(),
        "--sign",
    ]);
    assert!(enc_out.contains("Encrypted 512 bytes"), "output: {enc_out}");
    let ciphertext = fs::read(&encrypted).expect("read encrypted file");
    assert_ne!(ciphertext, payload, "ciphertext must not equal plaintext");

    let dec_out = bob.ok(&[
        "decrypt",
        "-f",
        encrypted.to_str().unwrap(),
        "-o",
        decrypted.to_str().unwrap(),
    ]);
    assert!(dec_out.contains("Signature: Verified"), "output: {dec_out}");
    assert_eq!(
        fs::read(&decrypted).expect("read decrypted file"),
        payload,
        "decrypted bytes must match the original binary file"
    );
}

#[test]
fn backup_create_prints_a_9x4_code_and_restore_recovers_the_key() {
    let home = Home::new("backup");
    init(&home, "Alice Example", "alice@example.com");
    let original = fs::read_to_string(home.file("identity.asc")).expect("read identity");

    let backup = home.file("alice-backup.asc");
    let created = home.ok(&["backup", "create", backup.to_str().unwrap()]);
    let code = setup_code(&created);
    assert!(
        created.contains("WRITE THIS DOWN"),
        "the user must be told to keep the code separate: {created}"
    );
    let backup_text = fs::read_to_string(&backup).expect("read backup file");
    assert!(
        backup_text.contains("-----BEGIN PGP MESSAGE-----"),
        "backup should be an encrypted PGP message"
    );
    assert!(!backup_text.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----"));

    let restored = home.file("restored.asc");
    let out = home.ok(&[
        "backup",
        "restore",
        backup.to_str().unwrap(),
        restored.to_str().unwrap(),
        "--code",
        &code,
    ]);
    assert!(out.contains("Key restored."), "output: {out}");
    assert_eq!(
        fs::read_to_string(&restored).expect("read restored key"),
        original,
        "the restored secret key must be byte-for-byte the original"
    );

    // The setup code can also arrive through the environment, so it never has
    // to appear in a shell history or process list.
    let env_restored = home.file("restored-from-env.asc");
    let env_out = home
        .command(&[
            "backup",
            "restore",
            backup.to_str().unwrap(),
            env_restored.to_str().unwrap(),
        ])
        .env("NEWPGP_SETUP_CODE", &code)
        .output()
        .expect("could not run newpgp");
    assert!(
        env_out.status.success(),
        "restore via NEWPGP_SETUP_CODE failed: {}",
        String::from_utf8_lossy(&env_out.stderr)
    );
    assert_eq!(
        fs::read_to_string(&env_restored).expect("read env-restored key"),
        original
    );
}

/// Send framed requests, close stdin, and collect all framed responses.
fn bridge_exchange(home: &Home, requests: &[Value]) -> Output {
    let mut child = home
        .command(&["bridge"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn newpgp bridge");
    {
        let stdin = child.stdin.as_mut().expect("bridge stdin is piped");
        for request in requests {
            write_frame(stdin, request);
        }
    }
    // EOF on stdin is the host's shutdown signal.
    drop(child.stdin.take());
    let output = finish(child);
    assert!(
        output.status.success(),
        "bridge exited {:?}: {}",
        output.status.code(),
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

#[test]
fn native_bridge_answers_framed_requests_and_exits_on_stdin_close() {
    let home = Home::new("bridge");
    let output = bridge_exchange(
        &home,
        &[
            json!({ "id": "1", "method": "ping", "params": {} }),
            json!({
                "id": "2",
                "method": "generateKey",
                "params": { "name": "Bridge User", "email": "bridge@example.com", "passphrase": "" }
            }),
        ],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames.len(), 2, "one response per request: {frames:?}");

    assert_eq!(frames[0]["id"].as_str(), Some("1"));
    assert_eq!(
        frames[0]["ok"].as_bool(),
        Some(true),
        "ping failed: {}",
        frames[0]
    );
    assert_eq!(frames[0]["result"]["ok"].as_bool(), Some(true));

    assert_eq!(frames[1]["id"].as_str(), Some("2"));
    assert_eq!(
        frames[1]["ok"].as_bool(),
        Some(true),
        "generateKey failed: {}",
        frames[1]
    );
    let fp = frames[1]["result"]["fingerprint"]
        .as_str()
        .expect("generateKey fingerprint")
        .to_string();
    assert_eq!(raw_fingerprint(&fp).len(), 40, "fingerprint: {fp}");
    assert!(frames[1]["result"]["publicKey"]
        .as_str()
        .expect("generateKey publicKey")
        .contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
    assert!(!frames[1]["result"]["privateKey"]
        .as_str()
        .expect("generateKey privateKey")
        .is_empty());

    // generateKey really persisted the key where the CLI would find it.
    let show = home.ok(&["key", "show"]);
    assert!(
        show.replace(' ', "").contains(&raw_fingerprint(&fp)),
        "bridge-generated key should be on disk"
    );

    // getKey resolves contacts learned via `key import` (the bridge stores a
    // freshly generated key as the "own identity" entry, with an empty email).
    let public_key = home.file("bridge-generated.asc");
    export_public_key(&home, &public_key);
    home.ok(&[
        "key",
        "import",
        "bridge@example.com",
        public_key.to_str().unwrap(),
    ]);
    let output = bridge_exchange(
        &home,
        &[json!({
            "id": "3",
            "method": "getKey",
            "params": { "email": "bridge@example.com" }
        })],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0]["id"].as_str(), Some("3"));
    assert_eq!(
        frames[0]["ok"].as_bool(),
        Some(true),
        "getKey failed: {}",
        frames[0]
    );
    assert_eq!(
        frames[0]["result"]["email"].as_str(),
        Some("bridge@example.com")
    );
    assert_eq!(
        raw_fingerprint(frames[0]["result"]["fingerprint"].as_str().unwrap()),
        raw_fingerprint(&fp)
    );
    assert!(!frames[0]["result"]["safetyNumber"]
        .as_str()
        .unwrap_or_default()
        .is_empty());
}

#[test]
fn bridge_get_key_immediately_after_generate_key() {
    let home = Home::new("bridge-getkey-bug");
    let output = bridge_exchange(
        &home,
        &[
            json!({
                "id": "1",
                "method": "generateKey",
                "params": { "name": "Bridge User", "email": "bridge@example.com", "passphrase": "" }
            }),
            json!({ "id": "2", "method": "getKey", "params": { "email": "bridge@example.com" } }),
        ],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);
    assert_eq!(
        frames[1]["ok"].as_bool(),
        Some(true),
        "getKey should find the key it just generated: {}",
        frames[1]
    );
}

#[test]
fn decrypt_with_the_wrong_home_fails_with_a_friendly_hint() {
    let sender = Home::new("wrong-home-sender");
    let recipient = Home::new("wrong-home-recipient");
    let stranger = Home::new("wrong-home-stranger");
    init(&sender, "Alice Example", "alice@example.com");
    init(&recipient, "Bob Example", "bob@example.com");
    init(&stranger, "Charlie Example", "charlie@example.com");

    let recipient_key = sender.file("bob.asc");
    export_public_key(&recipient, &recipient_key);
    sender.ok(&[
        "key",
        "import",
        "bob@example.com",
        recipient_key.to_str().unwrap(),
    ]);

    let encrypted = sender.file("secret.bin.pgp");
    fs::write(sender.file("secret.bin"), b"secret for Bob").expect("write plaintext");
    sender.ok(&[
        "encrypt",
        "-r",
        "bob@example.com",
        "-f",
        sender.file("secret.bin").to_str().unwrap(),
        "-o",
        encrypted.to_str().unwrap(),
    ]);

    let out = stranger.run(&[
        "decrypt",
        "-f",
        encrypted.to_str().unwrap(),
        "-o",
        stranger.file("should-not-exist.bin").to_str().unwrap(),
    ]);
    assert!(!out.status.success(), "wrong key must not decrypt");
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(
        stderr.contains("This message wasn't encrypted for the key you selected"),
        "human-readable error expected, got: {stderr}"
    );
    assert!(
        stderr.contains("Hint:") && stderr.contains("Check which identity is active"),
        "actionable hint expected, got: {stderr}"
    );
    assert!(!stranger.file("should-not-exist.bin").exists());
}

/// Import two different keys for one address. The second one must not become
/// active until the user explicitly confirms the change.
#[test]
fn changed_key_is_not_activated_without_confirmation() {
    let owner = Home::new("tofu-owner");
    let first = Home::new("tofu-first");
    let second = Home::new("tofu-second");
    let first_out = init(&first, "First Key", "friend@example.com");
    let second_out = init(&second, "Second Key", "friend@example.com");
    let first_fp = fingerprint(&first_out);
    let second_fp = fingerprint(&second_out);
    assert_ne!(first_fp, second_fp, "the two test keys must differ");

    let first_file = owner.file("first.asc");
    let second_file = owner.file("second.asc");
    export_public_key(&first, &first_file);
    export_public_key(&second, &second_file);

    owner.ok(&[
        "key",
        "import",
        "friend@example.com",
        first_file.to_str().unwrap(),
    ]);
    let changed = owner.ok(&[
        "key",
        "import",
        "friend@example.com",
        second_file.to_str().unwrap(),
    ]);
    assert!(
        changed.contains("WARNING") && changed.contains("HAS CHANGED"),
        "a loud warning is required: {changed}"
    );
    assert!(
        changed.contains("newpgp key confirm-change friend@example.com")
            && changed.contains("newpgp key reject-change friend@example.com"),
        "the user must be told how to resolve the change: {changed}"
    );

    let list = owner.ok(&["key", "list"]).replace(' ', "");
    assert!(
        list.contains(&first_fp),
        "old key should stay active: {list}"
    );
    assert!(
        !list.contains(&second_fp),
        "new key must not be active: {list}"
    );

    let confirmed = owner.ok(&["key", "confirm-change", "friend@example.com"]);
    assert!(
        confirmed.contains("Confirmed"),
        "confirmation output: {confirmed}"
    );
    let list = owner.ok(&["key", "list"]).replace(' ', "");
    assert!(
        list.contains(&second_fp),
        "confirmed key should be active: {list}"
    );
    assert!(
        !list.contains(&first_fp),
        "old key should no longer be active: {list}"
    );

    // A fresh run: rejecting the change keeps the original key.
    let rejector = Home::new("tofu-reject");
    rejector.ok(&[
        "key",
        "import",
        "friend@example.com",
        first_file.to_str().unwrap(),
    ]);
    rejector.ok(&[
        "key",
        "import",
        "friend@example.com",
        second_file.to_str().unwrap(),
    ]);
    let rejected = rejector.ok(&["key", "reject-change", "friend@example.com"]);
    assert!(
        rejected.contains("unchanged"),
        "rejection output: {rejected}"
    );
    let list = rejector.ok(&["key", "list"]).replace(' ', "");
    assert!(
        list.contains(&first_fp),
        "old key must survive rejection: {list}"
    );
    assert!(
        !list.contains(&second_fp),
        "rejected key must not be active: {list}"
    );
}

#[test]
fn bridge_get_key_without_email_returns_the_own_identity() {
    let home = Home::new("bridge-own-identity");
    let other = Home::new("bridge-earlier-contact");
    let other_out = init(&other, "Aardvark", "aardvark@example.com");
    let other_fp = fingerprint(&other_out);

    let output = bridge_exchange(
        &home,
        &[json!({
            "id": "1",
            "method": "generateKey",
            "params": {
                "name": "Zebra",
                "email": "zebra@example.com",
                "passphrase": ""
            }
        })],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);
    let own_fp = raw_fingerprint(frames[0]["result"]["fingerprint"].as_str().unwrap());

    // "aardvark@example.com" sorts before "zebra@example.com" (and before the
    // empty email used for the own identity), so the old code returned it.
    let other_key = home.file("aardvark.asc");
    export_public_key(&other, &other_key);
    home.ok(&[
        "key",
        "import",
        "aardvark@example.com",
        other_key.to_str().unwrap(),
    ]);

    let output = bridge_exchange(
        &home,
        &[json!({
            "id": "2",
            "method": "getKey",
            "params": {}
        })],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);
    assert_eq!(
        raw_fingerprint(frames[0]["result"]["fingerprint"].as_str().unwrap()),
        own_fp,
        "getKey without an email must return our own key"
    );
    assert_ne!(
        frames[0]["result"]["fingerprint"].as_str().unwrap(),
        other_fp
    );
}

#[test]
fn bridge_decrypt_and_sign_respect_the_generated_passphrase() {
    let home = Home::new("bridge-passphrase");
    let email = "locked@example.com";
    let passphrase = "correct horse battery staple";
    let output = bridge_exchange(
        &home,
        &[json!({
            "id": "1",
            "method": "generateKey",
            "params": { "name": "Locked User", "email": email, "passphrase": passphrase }
        })],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);

    let plaintext = "a secret only the right password opens";
    fs::write(home.file("plain.txt"), plaintext).expect("write plaintext");
    home.ok(&[
        "encrypt",
        "-r",
        email,
        "-f",
        home.file("plain.txt").to_str().unwrap(),
        "-o",
        home.file("cipher.bin").to_str().unwrap(),
    ]);
    let armored = armor_binary(&fs::read(home.file("cipher.bin")).expect("read ciphertext"));

    let good = bridge_exchange(
        &home,
        &[json!({
            "id": "2",
            "method": "decrypt",
            "params": { "armored": armored, "passphrase": passphrase }
        })],
    );
    let frames = parse_frames(&good.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);
    assert_eq!(
        frames[0]["result"]["plaintext"].as_str(),
        Some(plaintext),
        "the right passphrase must unlock the key"
    );

    let bad = bridge_exchange(
        &home,
        &[json!({
            "id": "3",
            "method": "decrypt",
            "params": { "armored": armored, "passphrase": "definitely wrong" }
        })],
    );
    let frames = parse_frames(&bad.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(false), "{}", frames[0]);
    let message = frames[0]["error"]["message"].as_str().unwrap_or_default();
    assert!(
        message.to_ascii_lowercase().contains("password"),
        "wrong passphrase should be explained: {message}"
    );

    let signed = bridge_exchange(
        &home,
        &[json!({
            "id": "4",
            "method": "sign",
            "params": { "data": "signed data", "passphrase": passphrase }
        })],
    );
    let frames = parse_frames(&signed.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);
    assert!(frames[0]["result"]["armored"]
        .as_str()
        .unwrap_or_default()
        .contains("-----BEGIN PGP SIGNATURE-----"));
}

#[test]
fn encrypt_accepts_a_fingerprint_as_a_recipient() {
    let alice = Home::new("fingerprint-alice");
    let bob = Home::new("fingerprint-bob");
    init(&alice, "Alice Example", "alice@example.com");
    let bob_out = init(&bob, "Bob Example", "bob@example.com");
    let bob_fp = fingerprint(&bob_out);

    let bob_key = alice.file("bob.asc");
    export_public_key(&bob, &bob_key);
    alice.ok(&[
        "key",
        "import",
        "bob@example.com",
        bob_key.to_str().unwrap(),
    ]);

    let spaced = bob_fp
        .as_bytes()
        .chunks(4)
        .map(|c| std::str::from_utf8(c).unwrap())
        .collect::<Vec<_>>()
        .join(" ");

    for (index, recipient) in [bob_fp.as_str(), spaced.as_str()].into_iter().enumerate() {
        let input = alice.file(&format!("fingerprint-{index}.txt"));
        let encrypted = alice.file(&format!("fingerprint-{index}.txt.pgp"));
        let decrypted = bob.file(&format!("fingerprint-{index}.decrypted.txt"));
        fs::write(&input, b"fingerprint recipient works").expect("write plaintext");
        let out = alice.ok(&[
            "encrypt",
            "-r",
            recipient,
            "-f",
            input.to_str().unwrap(),
            "-o",
            encrypted.to_str().unwrap(),
        ]);
        assert!(
            out.contains("Encrypted"),
            "encrypt to fingerprint failed: {out}"
        );
        bob.ok(&[
            "decrypt",
            "-f",
            encrypted.to_str().unwrap(),
            "-o",
            decrypted.to_str().unwrap(),
        ]);
        assert_eq!(
            fs::read(&decrypted).expect("read decrypted file"),
            b"fingerprint recipient works"
        );
    }
}

/// A key created with a password must be usable for signing. Compose always
/// signs, so `compose --password` is the regression case for the bug where a
/// hardcoded empty signer password made signing fail with "invalid input".
#[test]
fn protected_key_signs_compose_with_password() {
    let alice = Home::new("protected-compose-alice");
    let bob = Home::new("protected-compose-bob");
    let password = "open sesame";

    // Alice protects her key with a password at creation time.
    let alice_out = alice.ok(&[
        "init",
        "--name",
        "Alice Example",
        "--email",
        "alice@example.com",
        "--password",
        password,
    ]);
    assert!(
        alice_out.contains("Keep your password safe"),
        "init should warn about the password: {alice_out}"
    );
    let alice_fp = fingerprint(&alice_out);
    assert_eq!(alice_fp.len(), 40);

    // Bob has an ordinary unprotected key.
    init(&bob, "Bob Example", "bob@example.com");

    // Exchange public keys exactly as two users would.
    let alice_key = bob.file("alice.asc");
    let bob_key = alice.file("bob.asc");
    export_public_key(&alice, &alice_key);
    export_public_key(&bob, &bob_key);
    bob.ok(&[
        "key",
        "import",
        "alice@example.com",
        alice_key.to_str().unwrap(),
    ]);
    alice.ok(&[
        "key",
        "import",
        "bob@example.com",
        bob_key.to_str().unwrap(),
    ]);

    // Encrypt to the protected key from another home. Receiving a message
    // does not need Alice's password, but decrypting it does.
    fs::write(bob.file("for-alice.txt"), b"secret for Alice").expect("write plaintext");
    bob.ok(&[
        "encrypt",
        "-r",
        "alice@example.com",
        "-f",
        bob.file("for-alice.txt").to_str().unwrap(),
        "-o",
        bob.file("for-alice.bin").to_str().unwrap(),
    ]);
    let dec = alice.ok(&[
        "decrypt",
        "-f",
        bob.file("for-alice.bin").to_str().unwrap(),
        "-o",
        alice.file("for-alice.dec.txt").to_str().unwrap(),
        "--password",
        password,
    ]);
    assert!(
        dec.contains("Decrypted to"),
        "decrypting with the right password failed: {dec}"
    );
    assert_eq!(
        fs::read(alice.file("for-alice.dec.txt")).expect("read decrypted"),
        b"secret for Alice"
    );

    // Compose from the protected key. Compose always signs, so this used to
    // fail without a signer password.
    let composed = alice.ok(&[
        "compose",
        "--body",
        "signed reply from Alice",
        "-r",
        "bob@example.com",
        "--password",
        password,
    ]);
    let block = armor_block(&composed, "MESSAGE");
    assert!(
        block.starts_with("-----BEGIN PGP MESSAGE-----")
            && block.ends_with("-----END PGP MESSAGE-----"),
        "compose should produce a PGP message block: {composed}"
    );

    // The block is a real signed message: Bob can decrypt it and verify the
    // signature made by Alice's protected key.
    fs::write(bob.file("reply.asc"), &block).expect("write composed block");
    let verified = bob.ok(&[
        "decrypt",
        "-f",
        bob.file("reply.asc").to_str().unwrap(),
        "-o",
        bob.file("reply.dec.txt").to_str().unwrap(),
    ]);
    assert!(
        verified.contains("Signature: Verified"),
        "composed signature should verify: {verified}"
    );
    let reply = fs::read_to_string(bob.file("reply.dec.txt")).expect("read decrypted reply");
    assert!(
        reply.contains("signed reply from Alice"),
        "decrypted body should contain the composed text: {reply}"
    );
}

/// `encrypt --sign` must use the same password that protected the key.
#[test]
fn protected_key_signs_encrypt_with_password() {
    let alice = Home::new("protected-encrypt-alice");
    let bob = Home::new("protected-encrypt-bob");
    let password = "open sesame";

    let alice_out = alice.ok(&[
        "init",
        "--name",
        "Alice Example",
        "--email",
        "alice@example.com",
        "--password",
        password,
    ]);
    assert!(alice_out.contains("Keep your password safe"), "{alice_out}");
    init(&bob, "Bob Example", "bob@example.com");

    let alice_key = bob.file("alice.asc");
    let bob_key = alice.file("bob.asc");
    export_public_key(&alice, &alice_key);
    export_public_key(&bob, &bob_key);
    bob.ok(&[
        "key",
        "import",
        "alice@example.com",
        alice_key.to_str().unwrap(),
    ]);
    alice.ok(&[
        "key",
        "import",
        "bob@example.com",
        bob_key.to_str().unwrap(),
    ]);

    let input = alice.file("signed.txt");
    let encrypted = alice.file("signed.txt.pgp");
    let decrypted = bob.file("signed.dec.txt");
    fs::write(&input, b"sign this with a protected key").expect("write plaintext");

    // Signing without the password used to fail with "invalid input".
    let locked = alice.run(&[
        "encrypt",
        "-r",
        "bob@example.com",
        "-f",
        input.to_str().unwrap(),
        "-o",
        encrypted.to_str().unwrap(),
        "--sign",
    ]);
    assert!(
        !locked.status.success(),
        "signing a protected key without a password must fail"
    );

    let out = alice.ok(&[
        "encrypt",
        "-r",
        "bob@example.com",
        "-f",
        input.to_str().unwrap(),
        "-o",
        encrypted.to_str().unwrap(),
        "--sign",
        "--password",
        password,
    ]);
    assert!(out.contains("Encrypted"), "output: {out}");
    let verified = bob.ok(&[
        "decrypt",
        "-f",
        encrypted.to_str().unwrap(),
        "-o",
        decrypted.to_str().unwrap(),
    ]);
    assert!(
        verified.contains("Signature: Verified"),
        "signature should verify: {verified}"
    );
    assert_eq!(
        fs::read(&decrypted).expect("read decrypted file"),
        b"sign this with a protected key"
    );
}

/// The bridge must use a caller-supplied passphrase when signing during
/// `encrypt`. It must not try to guess one from the environment or the browser
/// extension: an explicitly supplied `passphrase` (or the `password` alias)
/// unlocks the signer, and no passphrase means signing fails.
#[test]
fn bridge_encrypt_uses_caller_supplied_passphrase_for_signing() {
    let home = Home::new("bridge-encrypt-passphrase");
    let email = "locked@example.com";
    let passphrase = "correct horse battery staple";

    let output = bridge_exchange(
        &home,
        &[json!({
            "id": "1",
            "method": "generateKey",
            "params": { "name": "Locked User", "email": email, "passphrase": passphrase }
        })],
    );
    let frames = parse_frames(&output.stdout);
    assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);

    // Both documented spellings of the passphrase unlock signing.
    for (index, key) in ["passphrase", "password"].into_iter().enumerate() {
        let mut params = json!({
            "plaintext": "a signed secret",
            "recipients": [email],
            "sign": true
        });
        params[key] = json!(passphrase);
        let output = bridge_exchange(
            &home,
            &[json!({
                "id": format!("{}", index + 2),
                "method": "encrypt",
                "params": params
            })],
        );
        let frames = parse_frames(&output.stdout);
        assert_eq!(
            frames[0]["ok"].as_bool(),
            Some(true),
            "encrypt with {key} should succeed: {}",
            frames[0]
        );
        let armored = frames[0]["result"]["armored"].as_str().unwrap_or_default();
        assert!(
            armored.contains("-----BEGIN PGP MESSAGE-----"),
            "encrypt should return an armored message: {armored}"
        );

        // The message is genuinely signed by the protected key: the bridge
        // reports a verified signature when the same passphrase is supplied.
        let decrypted = bridge_exchange(
            &home,
            &[json!({
                "id": "verify",
                "method": "decrypt",
                "params": { "armored": armored, "passphrase": passphrase }
            })],
        );
        let frames = parse_frames(&decrypted.stdout);
        assert_eq!(frames[0]["ok"].as_bool(), Some(true), "{}", frames[0]);
        assert_eq!(
            frames[0]["result"]["signature"].as_str(),
            Some("verified"),
            "the bridge encrypt signature should verify: {}",
            frames[0]
        );
    }

    // Without an explicit passphrase the protected signer stays locked, and
    // the bridge reports a failure rather than silently sending an empty one.
    let locked = bridge_exchange(
        &home,
        &[json!({
            "id": "no-password",
            "method": "encrypt",
            "params": {
                "plaintext": "a signed secret",
                "recipients": [email],
                "sign": true
            }
        })],
    );
    let frames = parse_frames(&locked.stdout);
    assert_eq!(
        frames[0]["ok"].as_bool(),
        Some(false),
        "signing a protected key without a passphrase must fail: {}",
        frames[0]
    );
    assert_eq!(
        frames[0]["error"]["code"].as_str(),
        Some("DECRYPT_FAILED"),
        "{}",
        frames[0]
    );
}
