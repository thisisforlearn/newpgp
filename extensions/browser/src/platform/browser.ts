// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * A deliberately small, typed slice of the WebExtension API.
 *
 * Keeping our own interface means the project compiles without pulling in
 * `@types/chrome` / `webextension-polyfill`. Both `chrome.*` (Chromium) and
 * `browser.*` (Firefox) satisfy it, and tests supply a mock implementation.
 */

export interface BrowserListener<T = unknown> {
  (value: T): void;
}

export interface BrowserEvent<T = unknown> {
  addListener(listener: BrowserListener<T>): void;
  removeListener(listener: BrowserListener<T>): void;
}

export interface NativePort {
  name: string;
  postMessage(message: unknown): void;
  onMessage: BrowserEvent<unknown>;
  onDisconnect: BrowserEvent<unknown>;
  disconnect(): void;
}

export interface BrowserStorageArea {
  get(keys?: string | string[] | null): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
  remove(keys: string | string[]): Promise<void>;
}

export interface BrowserTab {
  id?: number;
  url?: string;
  title?: string;
}

export interface BrowserApi {
  runtime: {
    connectNative(application: string): NativePort;
    sendMessage(message: unknown): Promise<unknown>;
    onMessage: BrowserEvent<unknown>;
    getURL(path: string): string;
    lastError?: { message?: string };
  };
  storage: {
    local: BrowserStorageArea;
    sync?: BrowserStorageArea;
  };
  tabs: {
    query(queryInfo: { active?: boolean; currentWindow?: boolean; url?: string[] }): Promise<BrowserTab[]>;
    sendMessage(tabId: number, message: unknown): Promise<unknown>;
  };
}

/**
 * Returns the ambient extension API. Throws a readable error on ordinary web
 * pages so that a misconfigured build fails loudly instead of silently.
 */
export function getBrowserApi(): BrowserApi {
  const scope = globalThis as unknown as { chrome?: BrowserApi; browser?: BrowserApi };
  const api = scope.chrome ?? scope.browser;
  if (!api) {
    throw new Error('NewPGP: browser extension APIs are unavailable in this page.');
  }
  return api;
}
