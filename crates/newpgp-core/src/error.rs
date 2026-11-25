// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
//! Friendly errors.
//!
//! A recurring complaint about traditional PGP tools is cryptic failures such as
//! "no secret key" or "unusable subkey". Every error in NewPGP carries a short,
//! plain-language explanation and, where possible, one concrete next step.

use std::fmt;

/// The single error type used by all NewPGP crates.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Error {
    /// Short technical identifier, useful in bug reports.
    pub code: &'static str,
    /// Message shown to a non-technical user.
    pub message: String,
    /// An optional concrete next step.
    pub hint: Option<String>,
}

impl Error {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            hint: None,
        }
    }

    pub fn with_hint(mut self, hint: impl Into<String>) -> Self {
        self.hint = Some(hint.into());
        self
    }

    /// A single sentence suitable for a toast or status bar.
    pub fn friendly(&self) -> String {
        match &self.hint {
            Some(hint) => format!("{} {}", self.message, hint),
            None => self.message.clone(),
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.friendly())?;
        if f.alternate() {
            write!(f, " [code: {}]", self.code)?;
        }
        Ok(())
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

/// Convert an rPGP error while keeping the user-facing message calm and useful.
pub(crate) fn pgp_error(ctx: &'static str) -> impl FnOnce(pgp::errors::Error) -> Error {
    move |source| {
        let detail = source.to_string();
        Error::new("openpgp", format!("{ctx}: {detail}"))
            .with_hint("If this came from pasted text, try 'Clean up pasted PGP text' first.")
    }
}
