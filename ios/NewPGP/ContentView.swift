// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Your key") {
                    if let identity = model.identity {
                        Label("Key ready", systemImage: "checkmark.shield.fill")
                        Text(identity.fingerprint).font(.footnote).textSelection(.enabled)
                    } else {
                        TextField("Your name", text: $name)
                        TextField("you@example.com", text: $email)
                        SecureField("Password (optional)", text: $password)
                        Button("Create my key") {
                            Task { await model.createIdentity(name: name, email: email, password: password) }
                        }
                    }
                }

                Section("Write") {
                    TextField("Recipients, separated by commas", text: $model.recipients)
                    TextEditor(text: $model.message).frame(minHeight: 140)
                    Toggle("Encrypted only", isOn: $model.pgpOnly)
                    Button("Prepare message") { Task { await model.prepare() } }
                    if !model.result.isEmpty {
                        TextEditor(text: .constant(model.result)).frame(minHeight: 120)
                        Button("Copy") { UIPasteboard.general.string = model.result }
                    }
                }

                Section("People") {
                    if model.contactList.isEmpty {
                        Text("No contacts yet. Keys are discovered from email automatically.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.contactList) { contact in
                            VStack(alignment: .leading) {
                                Text(contact.email).fontWeight(.semibold)
                                Text(contact.verification).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if !model.errorMessage.isEmpty {
                    Section("Notice") { Text(model.errorMessage).foregroundStyle(.orange) }
                }
            }
            .navigationTitle("NewPGP")
            .task { await model.refresh() }
        }
    }
}

#Preview {
    ContentView().environmentObject(AppModel())
}
