// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import Foundation

@MainActor
final class AppModel: ObservableObject {
    @Published var identity: IdentityInfo?
    @Published var contactList: [ContactInfo] = []
    @Published var message = ""
    @Published var recipients = ""
    @Published var pgpOnly = false
    @Published var result = ""
    @Published var errorMessage = ""

    /// Swapped for RustPgpBridge once the UniFFI bindings are generated.
    var bridge: PgpBridge = MockPgpBridge()

    func refresh() async {
        do {
            contactList = try await bridge.contacts()
            // The real bridge will expose a myIdentity() query; until then the
            // identity is set by createIdentity().
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createIdentity(name: String, email: String, password: String) async {
        do {
            identity = try await bridge.createIdentity(name: name, email: email, password: password)
            errorMessage = ""
        } catch { errorMessage = error.localizedDescription }
    }

    func prepare() async {
        do {
            let to = recipients.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }
            let composed = try await bridge.compose(text: message, recipients: to, pgpOnly: pgpOnly)
            result = composed.body
            errorMessage = composed.notices.joined(separator: " ")
        } catch { errorMessage = error.localizedDescription }
    }
}
