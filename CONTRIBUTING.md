# Contributing to NewPGP

Thank you — contributions are welcome from anyone, technical or not.

Official project and links: **https://github.com/thisisforlearn/newpgp**

## Author / maintainer

Vaibhav (legal name) — https://github.com/thisisforlearn
Contact: **nekomeowpurr@proton.me**

## Contributors

- **thisisforlearn** — primary maintainer and project owner (the GitHub handle of
  Vaibhav, listed above).
- **sudo-linux-neko** (@sudo-linux-neko) — contributor.

See [CONTRIBUTORS.md](CONTRIBUTORS.md) for the full list, roles and contact
details. More platforms are coming soon; official links are at
<https://github.com/thisisforlearn/newpgp>.

## Ways to send changes or feedback

You can submit changes in any of these ways:

1. **GitHub:** open an issue or pull request at
   https://github.com/thisisforlearn/newpgp
2. **Email:** send a patch, suggestion or bug report to **nekomeowpurr@proton.me**
   (please use "NewPGP" in the subject).
3. **Any other platform where this code is published:** the official project
   link above lists every place the code is available. Send the change through
   the same platform and it will be reviewed.
4. **Non-technical feedback:** plain-language descriptions of confusing screens
   or steps are just as valuable as code. Email them or open an issue.

> Never paste private keys, passwords, setup codes or real message contents into
> an issue, email draft or pull request.

## Before sending code

- Keep the project's rule: **no custom cryptography**. Use rPGP/OpenPGP.
- Add or update tests. Run:
  - `cargo fmt --all -- --check`
  - `cargo clippy --workspace --all-targets -- -D warnings`
  - `cargo test --workspace`
  - `npm test` inside `extensions/browser/`
- For security bugs, follow [SECURITY.md](SECURITY.md) and report privately.

## Licensing of contributions

By submitting a change you agree that your contribution is licensed under
**GPL-3.0-or-later**, the same license as the project (see `LICENSE` and `NOTICE.md`).
If you cannot agree to that, email the maintainer before sending the change so
we can discuss it.

## Co-authored commits

When more than one person worked on a change, credit everyone with a trailer in
the commit message. For the two people listed in [CONTRIBUTORS.md](CONTRIBUTORS.md),
use:

```
Co-authored-by: thisisforlearn <nekomeowpurr@proton.me>
Co-authored-by: sudo-linux-neko
```

Add a similar `Co-authored-by:` line for anyone else who helped with the change.

## Coming soon

NewPGP will be available on more platforms over time; setting each one up takes
time. The current status is in [README.md](README.md#what-works-today-v01-alpha)
and [ROADMAP.md](ROADMAP.md).
