# NOTICE — NewPGP

**Author and copyright holder:** Vaibhav (legal name)
**GitHub:** https://github.com/thisisforlearn
**Official project:** https://github.com/thisisforlearn/newpgp
**Contact for code, changes and security:** nekomeowpurr@proton.me
**License:** GNU General Public License, version 3 **or later** (GPL-3.0-or-later). See `LICENSE`.

## Contributors

- **thisisforlearn** — primary maintainer and project owner; the GitHub handle of
  Vaibhav: https://github.com/thisisforlearn
- **sudo-linux-neko** (@sudo-linux-neko) — contributor: https://github.com/sudo-linux-neko

See [CONTRIBUTORS.md](CONTRIBUTORS.md) and [AUTHORS.md](AUTHORS.md) for the roles
and contact details. Official project: https://github.com/thisisforlearn/newpgp.
More platforms are coming soon.

## Why GPL-3.0-or-later

GPLv2 was originally requested, but it has a real dependency nuance: a few
transitive libraries are **Apache-2.0-only** (for example `ring` through rustls,
`sync_wrapper`, and `borsh-derive`). The Free Software Foundation considers
Apache-2.0 compatible with **GPLv3**, but not GPLv2. Choosing GPL-3.0-or-later
makes the whole dependency set consistent and avoids that conflict.

We do not relicense third-party libraries. They keep their own licenses:

- Most Rust dependencies are offered as **"MIT OR Apache-2.0"** and are used
  under their MIT option (MIT is compatible with GPLv3).
- Apache-2.0 dependencies remain Apache-2.0 and are compatible with GPLv3.
- `ryu` is used under its BSL-1.0 option; BSL-1.0 is GPL-compatible.
- `ring` is Apache-2.0 AND ISC; both terms are compatible with GPLv3.

This is not legal advice. For questions, consult the FSF licensing lab
<https://www.gnu.org/licenses/license-list.html> or a lawyer.

## Third-party components

- rPGP (`pgp`) — MIT OR Apache-2.0 — https://github.com/rpgp/rpgp
- Tauri — MIT OR Apache-2.0 — https://tauri.app/
- Rust standard library and crates.io dependencies — see `Cargo.lock`.
- Browser extension dependencies — see `extensions/browser/package-lock.json`.
- Android keyboard uses Android SDK / Material components under Apache-2.0.
