// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import type { BrowserApi } from './platform/browser';
import { toFriendlyError, type FriendlyError } from './native/errors';
import type { RuntimeMessage } from './types';

export interface RuntimeResult<T> {
  ok: boolean;
  result?: T;
  error?: FriendlyError;
}

export function ok<T>(result: T): RuntimeResult<T> {
  return { ok: true, result };
}

export function fail(error: FriendlyError): RuntimeResult<never> {
  return { ok: false, error };
}

export function isRuntimeResult(value: unknown): value is RuntimeResult<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  return typeof (value as RuntimeResult<unknown>).ok === 'boolean';
}

/** Single entry point for popup/content -> service worker calls. */
export async function callBackground<T>(api: BrowserApi, message: RuntimeMessage): Promise<RuntimeResult<T>> {
  try {
    const response = await api.runtime.sendMessage(message);
    if (!isRuntimeResult(response)) {
      return fail({
        title: 'Something went wrong',
        message: 'NewPGP did not get a valid answer from its background worker.',
        hint: 'Reload the extension and try again.',
      });
    }
    return response as RuntimeResult<T>;
  } catch (error) {
    return fail(toFriendlyError(error));
  }
}
