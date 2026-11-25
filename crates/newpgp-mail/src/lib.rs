// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Email composition that gently introduces PGP without breaking anyone's day.
//!
//! Default behaviour is **send both**: the normal readable message plus an
//! appended encrypted block. Non-PGP friends still read the mail; PGP users
//! learn what the block looks like. A single toggle switches to PGP-only.

use newpgp_core::armor::{find_blocks, ArmorKind};
use newpgp_core::message::{decrypt_text, encrypt_text, EncryptionProfile, SignatureStatus};
use newpgp_core::Result;
use newpgp_discovery::autocrypt;
use pgp::composed::{SignedPublicKey, SignedSecretKey};

/// What to put in the outgoing message body.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ComposeMode {
    /// The readable message, then the encrypted block underneath. This is the
    /// default so that "nobody else uses PGP" never blocks a conversation.
    #[default]
    SendBoth,
    /// Only the encrypted block. Non-PGP recipients will not be able to read it.
    PgpOnly,
    /// Plain text only, with no PGP at all.
    PlainOnly,
}

impl ComposeMode {
    pub fn label(&self) -> &'static str {
        match self {
            Self::SendBoth => "Normal message + encrypted copy",
            Self::PgpOnly => "Encrypted only",
            Self::PlainOnly => "Normal message only",
        }
    }
}

/// Inputs to message composition.
pub struct ComposeRequest<'a> {
    pub body: &'a str,
    pub subject: &'a str,
    pub from_addr: &'a str,
    pub recipients: &'a [SignedPublicKey],
    pub signer: Option<(&'a SignedSecretKey, &'a str)>,
    pub mode: ComposeMode,
    pub prefer_encrypt_mutual: bool,
    pub profile: EncryptionProfile,
}

/// A ready-to-send message.
#[derive(Debug, Clone)]
pub struct ComposedMessage {
    /// The exact body to put in the outgoing email.
    pub body: String,
    /// Autocrypt header to add, if encryption was enabled.
    pub autocrypt_header: Option<String>,
    /// Autocrypt-Draft-State header value, if saving as a draft.
    pub draft_state_header: Option<String>,
    /// Plain-language notices shown in the UI before sending.
    pub notices: Vec<String>,
}

/// Compose an outgoing message.
pub fn compose(request: ComposeRequest<'_>) -> Result<ComposedMessage> {
    let mut notices = Vec::new();

    if request.mode == ComposeMode::PlainOnly || request.recipients.is_empty() {
        if request.mode == ComposeMode::SendBoth && request.recipients.is_empty() {
            notices.push(
                "No keys were found for the recipients, so this message is being sent normally."
                    .to_string(),
            );
        }
        return Ok(ComposedMessage {
            body: request.body.to_string(),
            autocrypt_header: None,
            draft_state_header: None,
            notices,
        });
    }

    let armored = encrypt_text(
        request.body,
        request.recipients,
        request.signer,
        request.profile,
    )?;

    let body = match request.mode {
        ComposeMode::PgpOnly => {
            notices.push(
                "This message is encrypted only. People without PGP will not be able to read it."
                    .to_string(),
            );
            armored.clone()
        }
        ComposeMode::SendBoth => {
            notices.push(
                "Your message includes both the readable text and an encrypted copy.".to_string(),
            );
            // Never delete or replace what the user typed.
            format!(
                "{}\n\n-- Encrypted copy (NewPGP) --\n{}\n",
                request.body.trim_end(),
                armored
            )
        }
        ComposeMode::PlainOnly => request.body.to_string(),
    };

    let own_cert = request
        .signer
        .map(|(secret, _)| SignedPublicKey::from(secret.clone()));
    let autocrypt_header = own_cert.as_ref().and_then(|cert| {
        autocrypt::build_header(cert, request.from_addr, request.prefer_encrypt_mutual).ok()
    });

    Ok(ComposedMessage {
        body,
        autocrypt_header,
        draft_state_header: Some(autocrypt::draft_state_header(
            request.mode == ComposeMode::PgpOnly,
            false,
            true,
        )),
        notices,
    })
}

/// Build the `multipart/encrypted` body described by RFC 3156, without the
/// outer mail headers. `boundary` is returned so the caller can set
/// `Content-Type` correctly.
pub fn pgp_mime_encrypted(armored: &str, boundary: &str) -> String {
    format!(
        "--{boundary}\r\n\
         Content-Type: application/pgp-encrypted\r\n\
         \r\n\
         Version: 1\r\n\
         \r\n\
         --{boundary}\r\n\
         Content-Type: application/octet-stream\r\n\
         \r\n\
         {armored}\r\n\
         --{boundary}--\r\n"
    )
}

/// A safe MIME boundary that cannot collide with the payload.
pub fn mime_boundary() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    format!("newpgp_{}", hex::encode(bytes))
}

/// Encrypt a draft to yourself before the mail provider ever sees it.
///
/// Autocrypt Level 1 requires this for drafts that may later be encrypted.
pub fn encrypted_draft(
    body: &str,
    own_public: &SignedPublicKey,
    own_secret: &SignedSecretKey,
    key_password: &str,
) -> Result<String> {
    let armored = encrypt_text(
        body,
        std::slice::from_ref(own_public),
        Some((own_secret, key_password)),
        EncryptionProfile::Compatible,
    )?;
    Ok(pgp_mime_encrypted(&armored, &mime_boundary()))
}

/// What we found when reading an incoming message.
#[derive(Debug, Clone)]
pub struct IncomingMessage {
    /// Decrypted plain text, when an encrypted block was present and readable.
    pub decrypted: Option<String>,
    /// Signature result for the decrypted block.
    pub signature: Option<SignatureStatus>,
    /// The readable text that was already in the message (for send-both mail).
    pub readable_text: String,
    /// Friendly warnings for the UI.
    pub warnings: Vec<String>,
}

/// Read an incoming message body.
///
/// For send-both messages we keep the readable text and also decrypt the
/// attached block so the user sees the verified copy.
pub fn read_incoming(
    body: &str,
    secret: &SignedSecretKey,
    key_password: &str,
    known_keys: &[SignedPublicKey],
) -> Result<IncomingMessage> {
    let mut readable = body.to_string();
    let mut decrypted = None;
    let mut signature = None;
    let mut warnings = Vec::new();

    for block in find_blocks(body)
        .into_iter()
        .filter(|b| b.kind == ArmorKind::Message)
    {
        readable = readable.replace(&block.text, "");
        match decrypt_text(block.text.as_bytes(), secret, key_password, known_keys) {
            Ok((text, status)) => {
                if decrypted.is_none() {
                    decrypted = Some(text);
                    signature = Some(status);
                }
            }
            Err(e) => warnings.push(format!(
                "Could not decrypt an attached block: {}",
                e.friendly()
            )),
        }
    }

    let readable_text = readable
        .lines()
        .filter(|line| !line.trim().starts_with("-- Encrypted copy"))
        .collect::<Vec<_>>()
        .join("\n")
        .trim()
        .to_string();

    Ok(IncomingMessage {
        decrypted,
        signature,
        readable_text,
        warnings,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use newpgp_core::keys::generate_identity;

    fn id(email: &str) -> newpgp_core::Identity {
        generate_identity("Test Person", email, "").unwrap()
    }

    #[test]
    fn send_both_keeps_the_original_text() {
        let alice = id("alice@example.com");
        let bob = id("bob@example.com");
        let original = "Hi Bob, are we still on for lunch?";
        let composed = compose(ComposeRequest {
            body: original,
            subject: "Lunch",
            from_addr: "alice@example.com",
            recipients: std::slice::from_ref(&bob.public),
            signer: Some((&alice.secret, "")),
            mode: ComposeMode::SendBoth,
            prefer_encrypt_mutual: false,
            profile: EncryptionProfile::Compatible,
        })
        .unwrap();

        assert!(composed.body.starts_with(original));
        assert!(composed.body.contains("-----BEGIN PGP MESSAGE-----"));
        assert!(composed.autocrypt_header.is_some());
        assert!(composed.notices[0].contains("readable text"));
    }

    #[test]
    fn pgp_only_does_not_include_plaintext() {
        let bob = id("bob@example.com");
        let secret_text = "Top secret meeting location";
        let composed = compose(ComposeRequest {
            body: secret_text,
            subject: "Secret",
            from_addr: "alice@example.com",
            recipients: std::slice::from_ref(&bob.public),
            signer: None,
            mode: ComposeMode::PgpOnly,
            prefer_encrypt_mutual: false,
            profile: EncryptionProfile::Compatible,
        })
        .unwrap();
        assert!(!composed.body.contains(secret_text));
        assert!(composed.body.starts_with("-----BEGIN PGP MESSAGE-----"));
    }

    #[test]
    fn no_recipients_falls_back_to_plain_with_a_notice() {
        let composed = compose(ComposeRequest {
            body: "Hello",
            subject: "Hi",
            from_addr: "alice@example.com",
            recipients: &[],
            signer: None,
            mode: ComposeMode::SendBoth,
            prefer_encrypt_mutual: false,
            profile: EncryptionProfile::Compatible,
        })
        .unwrap();
        assert_eq!(composed.body, "Hello");
        assert!(composed.notices[0].contains("normally"));
    }

    #[test]
    fn reading_a_send_both_message_gives_both_views() {
        let alice = id("alice@example.com");
        let bob = id("bob@example.com");
        let composed = compose(ComposeRequest {
            body: "Readable text",
            subject: "Hi",
            from_addr: "alice@example.com",
            recipients: std::slice::from_ref(&bob.public),
            signer: Some((&alice.secret, "")),
            mode: ComposeMode::SendBoth,
            prefer_encrypt_mutual: false,
            profile: EncryptionProfile::Compatible,
        })
        .unwrap();

        let incoming = read_incoming(
            &composed.body,
            &bob.secret,
            "",
            std::slice::from_ref(&alice.public),
        )
        .unwrap();
        assert_eq!(incoming.decrypted.as_deref(), Some("Readable text"));
        assert_eq!(incoming.readable_text, "Readable text");
        assert!(matches!(
            incoming.signature,
            Some(SignatureStatus::Verified(_))
        ));
    }

    #[test]
    fn mime_structure_has_both_parts() {
        let mime = pgp_mime_encrypted("ARMORED", "boundary123");
        assert!(mime.contains("Content-Type: application/pgp-encrypted"));
        assert!(mime.contains("Version: 1"));
        assert!(mime.contains("Content-Type: application/octet-stream"));
        assert!(mime.contains("ARMORED"));
        assert!(mime.ends_with("--boundary123--\r\n"));
    }

    #[test]
    fn encrypted_draft_roundtrips() {
        let alice = id("alice@example.com");
        let draft = encrypted_draft("Draft body", &alice.public, &alice.secret, "").unwrap();
        let blocks = find_blocks(&draft);
        assert_eq!(blocks.len(), 1);
        let (text, _) = decrypt_text(blocks[0].text.as_bytes(), &alice.secret, "", &[]).unwrap();
        assert_eq!(text, "Draft body");
    }
}
