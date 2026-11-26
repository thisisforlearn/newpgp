// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * MV3 service worker. All crypto is delegated to the native host; this worker
 * only validates messages, normalizes armor, and returns friendly errors.
 */

import { loadSettings, saveSettings } from '../composition/settings';
import { normalizeArmor } from '../crypto/normalizeArmor';
import { toFriendlyError } from '../native/errors';
import { NativeMessagingClient } from '../native/NativeMessagingClient';
import { getBrowserApi, type MessageSender, type SendResponse } from '../platform/browser';
import { fail, ok, type RuntimeResult } from '../messages';
import type { RuntimeMessage, Settings } from '../types';

const api = getBrowserApi();
const bridge = new NativeMessagingClient(api);

async function handle(message: RuntimeMessage): Promise<RuntimeResult<unknown>> {
  switch (message.type) {
    case 'PING': {
      await bridge.ping();
      return ok({ connected: true });
    }
    case 'DISCOVER_KEY': {
      const email = message.email.trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        return fail({
          title: 'Please check that email address',
          message: 'It does not look like a complete email address.',
          hint: 'Try something like name@example.com.',
        });
      }
      return ok(await bridge.discoverKey({ email }));
    }
    case 'ENCRYPT': {
      const settings = await loadSettings(api);
      if (!settings.enabled) {
        return fail({
          title: 'NewPGP is paused',
          message: 'Encryption is switched off, so your message was left as you typed it.',
          hint: 'Turn NewPGP back on in the popup if you want to encrypt.',
        });
      }
      const result = await bridge.encrypt({
        plaintext: message.plaintext,
        recipients: message.recipients,
        // The shipped CLI/bridge signs with an empty password, so signing a
        // message with a password-protected key fails the whole encryption.
        // Disable signing until passphrase plumbing and a UI exist. This only
        // drops the signature; the plaintext still goes solely to our native
        // host and is never logged or persisted.
        sign: false,
        ...(message.passphrase === undefined ? {} : { passphrase: message.passphrase }),
      });
      // The mode is applied by the content script, which keeps the draft in
      // memory. We return it here so there is a single source of truth.
      return ok({ ...result, sendMode: settings.sendMode });
    }
    case 'DECRYPT_ARMORED': {
      const normalized = normalizeArmor(message.armored);
      if (!normalized.found) {
        return fail({
          title: 'This does not look like a complete PGP block',
          message: 'We could not find a full encrypted block in the selected text.',
          hint: 'Make sure the copy includes both the BEGIN and END lines.',
        });
      }
      return ok(await bridge.decrypt({ armored: normalized.armored, verify: true }));
    }
    case 'GET_SETTINGS': {
      return ok(await loadSettings(api));
    }
    case 'SET_SETTINGS': {
      const settings: Settings = message.settings;
      await saveSettings(api, settings);
      return ok(settings);
    }
    default: {
      return fail({
        title: 'Unknown request',
        message: 'NewPGP did not recognise this request.',
      });
    }
  }
}

api.runtime.onMessage.addListener((raw: unknown, sender?: MessageSender, sendResponse?: SendResponse) => {
  const reply = (value: unknown): void => sendResponse?.(value);

  // Only our own extension may drive the bridge. A foreign sender id must
  // never be able to ask us to encrypt/decrypt on its behalf.
  if (sender?.id && api.runtime.id && sender.id !== api.runtime.id) {
    reply(
      fail({
        title: 'Request not accepted',
        message: 'NewPGP only answers requests from its own extension pages.',
      }),
    );
    return false;
  }
  if (typeof raw !== 'object' || raw === null) {
    reply(undefined);
    return false;
  }
  const message = raw as RuntimeMessage;
  if (typeof message.type !== 'string') {
    reply(undefined);
    return false;
  }

  // Chrome only supports Promise replies from version 148 onwards, which is
  // still rolling out. Answer through sendResponse instead and keep the
  // message channel open while the async handler is running.
  void handle(message)
    .then((result) => reply(result))
    .catch((error: unknown) => reply(fail(toFriendlyError(error))));
  return true;
});
