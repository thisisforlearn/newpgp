// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import Foundation

/// Contract shared by the real Rust-backed bridge and the mock used in tests.
/// The Rust core will be exposed through UniFFI (crates/newpgp-mobile) in
/// Phase 2. This scaffold deliberately contains no cryptography of its own.
protocol PgpBridge {
    func createIdentity(name: String, email: String, password: String) async throws -> IdentityInfo
    func publicKey() async throws -> String
    func discoverKey(email: String) async throws -> ContactInfo
    func encrypt(text: String, recipients: [String]) async throws -> String
    func decrypt(armored: String) async throws -> DecryptedInfo
    func compose(text: String, recipients: [String], pgpOnly: Bool) async throws -> ComposeResult
    func contacts() async throws -> [ContactInfo]
    func verifySafetyNumber(email: String, code: String) async throws
}

struct IdentityInfo: Identifiable, Hashable {
    var id: String { fingerprint }
    let fingerprint: String
    let publicKey: String
    let safetyNumber: String
}

struct ContactInfo: Identifiable, Hashable {
    var id: String { email }
    let email: String
    let fingerprint: String
    let source: String
    let verification: String
    let safetyNumber: String
}

struct DecryptedInfo: Hashable {
    let text: String
    let signature: String
}

struct ComposeResult: Hashable {
    let body: String
    let notices: [String]
}

enum BridgeError: LocalizedError {
    case notConfigured
    case noKey
    case unknownRecipient(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "The local NewPGP engine is not configured yet."
        case .noKey: return "You haven't created your key yet."
        case .unknownRecipient(let email): return "No key found for \(email)."
        }
    }
}

/// Placeholder that fails closed. Replace with the UniFFI-generated bindings.
final class RustPgpBridge: PgpBridge {
    func createIdentity(name: String, email: String, password: String) async throws -> IdentityInfo {
        throw BridgeError.notConfigured
    }
    func publicKey() async throws -> String { throw BridgeError.notConfigured }
    func discoverKey(email: String) async throws -> ContactInfo { throw BridgeError.notConfigured }
    func encrypt(text: String, recipients: [String]) async throws -> String { throw BridgeError.notConfigured }
    func decrypt(armored: String) async throws -> DecryptedInfo { throw BridgeError.notConfigured }
    func compose(text: String, recipients: [String], pgpOnly: Bool) async throws -> ComposeResult {
        throw BridgeError.notConfigured
    }
    func contacts() async throws -> [ContactInfo] { throw BridgeError.notConfigured }
    func verifySafetyNumber(email: String, code: String) async throws { throw BridgeError.notConfigured }
}

/// In-memory mock used by unit tests. Produces clearly labelled fake data.
final class MockPgpBridge: PgpBridge {
    private var identity: IdentityInfo?
    private var contacts: [String: ContactInfo] = [:]

    func createIdentity(name: String, email: String, password: String) async throws -> IdentityInfo {
        let info = IdentityInfo(
            fingerprint: "MOCK-FINGERPRINT-\(email)",
            publicKey: "-----BEGIN MOCK PUBLIC KEY-----\n\(email)\n-----END MOCK PUBLIC KEY-----",
            safetyNumber: "MOCK SAFETY NUMBER"
        )
        identity = info
        return info
    }

    func publicKey() async throws -> String {
        guard let identity else { throw BridgeError.noKey }
        return identity.publicKey
    }

    func discoverKey(email: String) async throws -> ContactInfo {
        let contact = ContactInfo(
            email: email,
            fingerprint: "MOCK-FPR-\(email)",
            source: "Mock",
            verification: "First time seen",
            safetyNumber: "MOCK SAFETY NUMBER"
        )
        contacts[email] = contact
        return contact
    }

    func encrypt(text: String, recipients: [String]) async throws -> String {
        guard recipients.allSatisfy({ contacts[$0] != nil }) else {
            throw BridgeError.unknownRecipient(recipients.first ?? "")
        }
        return "-----BEGIN MOCK PGP MESSAGE-----\n\(text.count) bytes\n-----END MOCK PGP MESSAGE-----"
    }

    func decrypt(armored: String) async throws -> DecryptedInfo {
        DecryptedInfo(text: "MOCK decrypted text", signature: "Not signed")
    }

    func compose(text: String, recipients: [String], pgpOnly: Bool) async throws -> ComposeResult {
        let encrypted = try await encrypt(text: text, recipients: recipients)
        return ComposeResult(
            body: pgpOnly ? encrypted : "\(text)\n\n-- Encrypted copy --\n\(encrypted)",
            notices: ["Mock bridge — no real encryption performed."]
        )
    }

    func contacts() async throws -> [ContactInfo] { Array(contacts.values) }

    func verifySafetyNumber(email: String, code: String) async throws {
        guard contacts[email] != nil else { throw BridgeError.unknownRecipient(email) }
    }
}
