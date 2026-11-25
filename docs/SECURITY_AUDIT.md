# Security audit exceptions

NewPGP's `audit` CI job runs `cargo audit --file Cargo.lock` and is a real,
blocking check: it fails on any unignored advisory. Two advisories are
currently ignored, by ID only, in [`.cargo/audit.toml`](../.cargo/audit.toml)
and acknowledged in [`deny.toml`](../deny.toml). This page records why, and the
conditions under which the exceptions must be revisited.

## The two advisories

### RUSTSEC-2026-0387 — `crypto-bigint` 0.5.5

[Multiple timing variability issues in operations intended to be
constant-time](https://rustsec.org/advisories/RUSTSEC-2026-0387.html).
Depending on the compiler and CPU, operations intended to be constant-time
(subtraction, zero counting, division, Montgomery multiplication, `UintRef`
equality, and `BoxedMontyForm::invert`) may take secret-dependent branches and
could leak information about secret integers through timing or
microarchitectural side channels. Fixed in `crypto-bigint` **>= 0.7.6**.

### RUSTSEC-2023-0071 — `rsa` 0.9.10

[Marvin Attack: potential key recovery through timing
sidechannels](https://rustsec.org/advisories/RUSTSEC-2023-0071.html)
(CVE-2023-49092, GHSA-c38w-74pg-36hr, GHSA-4grx-2x9w-596c). A non-constant-time
implementation can leak private-key information observable over the network,
potentially allowing key recovery. Fixed in `rsa` **>= 0.10.0**.

## Why they are not reachable in NewPGP

Both crates are **transitive dependencies of rPGP** (`pgp` 0.21.1, the latest
released version at the time of writing). They are pulled in for RSA support
and legacy integer arithmetic inside rPGP, not because NewPGP uses them.

NewPGP's key generation and every normal operation use rPGP exclusively for:

- **Ed25519 signing** (signing and verification), and
- **Curve25519 / X25519 encryption** (key agreement and encryption).

NewPGP never calls rPGP's RSA signing, encryption, or decryption APIs. The RSA
and affected `crypto-bigint` code paths remain in the dependency graph only for
**legacy message/key parsing compatibility** (reading OpenPGP data produced by
other implementations). Parsing does not perform RSA private-key operations,
so the timing side channels described above are not exercised by NewPGP code.

Upgrading either crate would require a semver-breaking rPGP upgrade (rPGP's
current release constrains `rsa` 0.9.x and `crypto-bigint` 0.5.x). We do not
carry a local fork or patched dependency, and we do not suppress the findings
by severity, target, or category filters — only the two advisory IDs are
ignored.

## Review condition and expiry

These ignores are temporary and **must be reviewed no later than 2027-02-24**
(90 days from the date this document was written), or earlier if any of the
following happens:

- rPGP upgrades to `rsa` >= 0.10.0 and `crypto-bigint` >= 0.7.6 (then remove
  both ignores);
- NewPGP adds any RSA key generation, signing, encryption, or decryption
  support (then this exception no longer applies and must be removed before
  that code ships);
- a new advisory affects these crates or a different NewPGP code path;
- the `pgp` dependency version changes for any reason.

The manual review conditions above are the reminder to clean up the config:
run `cargo audit` after every dependency change and remove either ignore as soon
as it stops matching the lockfile.

## How to re-check

Run the same check CI runs, from the repository root:

```sh
cargo install cargo-audit --locked   # once
cargo audit --file Cargo.lock
```

To see the underlying dependency paths, `cargo audit` prints inverse dependency
trees by default. To view all advisories without the config exceptions, run it
from a directory that has no `.cargo/audit.toml` and pass the lockfile by path.
`cargo deny check --all-features` performs the complementary license/ban/source
checks; its `[advisories] ignore` list mirrors the two entries above.
