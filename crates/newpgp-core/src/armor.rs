// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Tolerant ASCII-armor handling.
//!
//! PGP text is routinely mangled by mail clients: `>` quote markers, HTML
//! wrappers, quoted-printable `=3D`, smart quotes, trailing spaces and broken
//! line endings. Instead of showing "invalid armor", we repair the common
//! cases before handing the text to the parser.

use crate::error::{Error, Result};

pub const MESSAGE_BEGIN: &str = "-----BEGIN PGP MESSAGE-----";
pub const MESSAGE_END: &str = "-----END PGP MESSAGE-----";
pub const PUBLIC_BEGIN: &str = "-----BEGIN PGP PUBLIC KEY BLOCK-----";
pub const PUBLIC_END: &str = "-----END PGP PUBLIC KEY BLOCK-----";
pub const SECRET_BEGIN: &str = "-----BEGIN PGP PRIVATE KEY BLOCK-----";
pub const SECRET_END: &str = "-----END PGP PRIVATE KEY BLOCK-----";
pub const SIGNATURE_BEGIN: &str = "-----BEGIN PGP SIGNATURE-----";
pub const SIGNATURE_END: &str = "-----END PGP SIGNATURE-----";
pub const SIGNED_BEGIN: &str = "-----BEGIN PGP SIGNED MESSAGE-----";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArmorKind {
    Message,
    PublicKey,
    SecretKey,
    Signature,
    SignedMessage,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArmorBlock {
    pub kind: ArmorKind,
    pub text: String,
}

/// Find every armored block in a piece of text, in order.
///
/// Both marker styles are tolerated: uppercase (standard) and the rare
/// lowercase variant produced by a few broken clients.
pub fn find_blocks(input: &str) -> Vec<ArmorBlock> {
    let flat = input.replace("\r\n", "\n").replace('\r', "\n");
    let upper = flat.to_ascii_uppercase();
    let mut blocks = Vec::new();
    let mut pos = 0usize;

    while pos < upper.len() {
        let Some(rel) = upper[pos..].find("-----BEGIN PGP ") else {
            break;
        };
        let begin = pos + rel;
        let line_end = upper[begin..]
            .find('\n')
            .map(|i| begin + i + 1)
            .unwrap_or(upper.len());
        let begin_line = flat[begin..line_end].trim();

        let Some((kind, end_marker)) = classify(begin_line) else {
            pos = line_end;
            continue;
        };

        let end_upper = end_marker.to_ascii_uppercase();
        let Some(rel_end) = upper[line_end..].find(&end_upper) else {
            break;
        };
        let end = line_end + rel_end;
        let after_end = upper[end..]
            .find('\n')
            .map(|i| end + i + 1)
            .unwrap_or(upper.len());

        blocks.push(ArmorBlock {
            kind,
            text: flat[begin..after_end].trim_end().to_string(),
        });
        pos = after_end;
    }

    blocks
}

fn classify(line: &str) -> Option<(ArmorKind, &'static str)> {
    let l = line.trim().to_ascii_uppercase();
    if l.starts_with(MESSAGE_BEGIN) {
        Some((ArmorKind::Message, MESSAGE_END))
    } else if l.starts_with(PUBLIC_BEGIN) {
        Some((ArmorKind::PublicKey, PUBLIC_END))
    } else if l.starts_with(SECRET_BEGIN) {
        Some((ArmorKind::SecretKey, SECRET_END))
    } else if l.starts_with(SIGNATURE_BEGIN) {
        Some((ArmorKind::Signature, SIGNATURE_END))
    } else if l.starts_with(SIGNED_BEGIN) {
        Some((ArmorKind::SignedMessage, "-----END PGP SIGNED MESSAGE-----"))
    } else {
        None
    }
}

/// Repair an armored block so a strict OpenPGP parser accepts it.
///
/// The function is intentionally conservative: it fixes presentation damage
/// but never changes base64 characters, because doing so would corrupt data.
pub fn normalize_armor(input: &str) -> Result<String> {
    let blocks = find_blocks(input);
    let block = blocks
        .iter()
        .find(|b| b.kind == ArmorKind::Message)
        .or_else(|| blocks.first())
        .ok_or_else(|| {
            Error::new(
                "no_armor_found",
                "I couldn't find an encrypted PGP message in that text.",
            )
            .with_hint("Look for a block starting with -----BEGIN PGP MESSAGE----- and paste the whole block.")
        })?;
    normalize_block(block)
}

/// Normalize all armored blocks found in `input`.
pub fn normalize_all(input: &str) -> Result<Vec<String>> {
    find_blocks(input).iter().map(normalize_block).collect()
}

fn normalize_block(block: &ArmorBlock) -> Result<String> {
    let (begin, end) = match block.kind {
        ArmorKind::Message => (MESSAGE_BEGIN, MESSAGE_END),
        ArmorKind::PublicKey => (PUBLIC_BEGIN, PUBLIC_END),
        ArmorKind::SecretKey => (SECRET_BEGIN, SECRET_END),
        ArmorKind::Signature => (SIGNATURE_BEGIN, SIGNATURE_END),
        ArmorKind::SignedMessage => (SIGNED_BEGIN, "-----END PGP SIGNED MESSAGE-----"),
    };

    let flat = block.text.replace("\r\n", "\n").replace('\r', "\n");
    let mut body = Vec::new();
    let mut in_body = false;

    for raw in flat.lines() {
        let mut line = strip_wrapper(raw);
        // Quoted-printable `=3D` means `=`, but a 5-character armor CRC line
        // (`=xxxx`) may legitimately begin with `=3D`. Never rewrite that line.
        if !is_crc_line(&line) {
            line = line.replace("=3D", "=");
        }
        let upper = line.to_ascii_uppercase();

        if upper.starts_with("-----BEGIN PGP ") {
            in_body = true;
            continue;
        }
        if upper.starts_with("-----END PGP ") {
            break;
        }
        if !in_body {
            continue;
        }
        // Armor headers (Version:, Comment:, Hash:, Charset:) and the blank
        // line that terminates them can be dropped safely.
        if line.is_empty() {
            continue;
        }
        if line.contains(':') && !looks_like_base64(&line) {
            continue;
        }
        if !looks_like_base64(&line) && line != "=" {
            // CRC line "=abcd" is handled by looks_like_base64 below via '='.
            continue;
        }
        body.push(line.split_off(0));
    }

    if body.is_empty() {
        return Err(Error::new(
            "empty_armor",
            "The PGP block is empty or was damaged in transit.",
        )
        .with_hint("Ask the sender to resend it as an attachment instead of pasted text."));
    }

    let mut out = String::with_capacity(block.text.len());
    out.push_str(begin);
    out.push('\n');
    // RFC 9580 armor has a blank line after the header block. rPGP's parser
    // requires it even when there are no armor headers.
    out.push('\n');
    out.push_str(&body.join("\n"));
    out.push('\n');
    out.push_str(end);
    out.push('\n');
    Ok(out)
}

/// Remove markup that mail clients and chat apps add around pasted armor.
fn strip_wrapper(raw: &str) -> String {
    let s = raw.trim();
    // Quoted reply markers: "> ", ">> ", ">".
    let mut s = s;
    while let Some(rest) = s.strip_prefix('>') {
        s = rest.strip_prefix(' ').unwrap_or(rest).trim_start();
    }
    // Some forums prefix quoted lines with "| " or "+ ".
    if let Some(rest) = s.strip_prefix("| ").or_else(|| s.strip_prefix("+ ")) {
        s = rest.trim_start();
    }
    // HTML tags on their own line, e.g. <br> or <div>.
    if s.starts_with('<') && s.ends_with('>') && !s.contains("-----") {
        return String::new();
    }
    // Smart punctuation only. Quoted-printable decoding happens in
    // `normalize_block`, where we can protect legitimate CRC lines.
    s.chars()
        .map(|c| match c {
            '’' | '‘' => '\'',
            '“' | '”' => '"',
            '\u{00a0}' => ' ',
            _ => c,
        })
        .collect::<String>()
        .trim_end()
        .to_string()
}

/// An OpenPGP armor checksum line: `=` followed by four base64 characters.
fn is_crc_line(line: &str) -> bool {
    let bytes = line.as_bytes();
    bytes.len() == 5
        && bytes[0] == b'='
        && bytes[1..]
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || *b == b'+' || *b == b'/')
}

fn looks_like_base64(line: &str) -> bool {
    line.starts_with('=')
        || line
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '/' || c == '=')
}

#[cfg(test)]
mod tests {
    use super::*;

    const BLOCK: &str =
        "-----BEGIN PGP MESSAGE-----\n\nhQEOA1Zvb25kYXJl\n=abcd\n-----END PGP MESSAGE-----";

    #[test]
    fn finds_a_clean_block() {
        let blocks = find_blocks(&format!("hello\n{BLOCK}\nbye"));
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].kind, ArmorKind::Message);
    }

    #[test]
    fn repairs_quote_markers_and_html() {
        let pasted = format!(
            "<div>On Tuesday Bob wrote:</div>\n{}<br>\n{}<br>\n{}<br>\n{}<br>\n",
            strip_prefix_marker(BLOCK, 0),
            strip_prefix_marker(BLOCK, 1),
            strip_prefix_marker(BLOCK, 2),
            strip_prefix_marker(BLOCK, 3),
        );
        let normalized = normalize_armor(&pasted).unwrap();
        assert!(normalized.starts_with(MESSAGE_BEGIN));
        assert!(normalized.contains("hQEOA1Zvb25kYXJl"));
        assert!(normalized.trim_end().ends_with(MESSAGE_END));
        assert!(!normalized.contains('>'));
        assert!(!normalized.contains("<br>"));
    }

    fn strip_prefix_marker(block: &str, n: usize) -> String {
        let prefix = "> ".repeat(n);
        block
            .lines()
            .map(|l| format!("{prefix}{l}"))
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn rejects_text_without_armor() {
        let err = normalize_armor("just a normal email").unwrap_err();
        assert_eq!(err.code, "no_armor_found");
        assert!(err.friendly().contains("BEGIN PGP MESSAGE"));
    }

    #[test]
    fn repairs_quoted_printable_equals() {
        let pasted = BLOCK
            .lines()
            .map(|line| {
                if line.starts_with("-----") {
                    line.to_string()
                } else {
                    line.replace('=', "=3D")
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        let normalized = normalize_armor(&pasted).unwrap();
        assert!(normalized.contains("=abcd"));
    }

    #[test]
    fn a_real_crc_line_starting_with_3d_is_preserved() {
        let block =
            "-----BEGIN PGP MESSAGE-----\n\nhQEOA1Zvb25kYXJl\n=3Dab\n-----END PGP MESSAGE-----";
        let normalized = normalize_armor(block).unwrap();
        assert!(normalized.contains("\n=3Dab\n"));
    }

    #[test]
    fn quoted_printable_crc_is_still_decoded() {
        let pasted = BLOCK
            .lines()
            .map(|line| {
                if line.starts_with("-----") || line.is_empty() {
                    line.to_string()
                } else {
                    // The CRC `=abcd` becomes `=3Dabcd` in quoted-printable.
                    line.replace('=', "=3D")
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        let normalized = normalize_armor(&pasted).unwrap();
        assert!(normalized.contains("\n=abcd\n"));
    }

    proptest::proptest! {
        #[test]
        fn normalization_never_panics(s in "\\PC{0,500}") {
            let _ = normalize_armor(&s);
        }
    }
}
