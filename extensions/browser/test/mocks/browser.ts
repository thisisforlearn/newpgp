// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import type {
  BrowserApi,
  BrowserEvent,
  BrowserListener,
  BrowserStorageArea,
  NativePort,
} from '../../src/platform/browser';

export class MockEvent<T = unknown> implements BrowserEvent<T> {
  readonly listeners = new Set<BrowserListener<T>>();

  addListener(listener: BrowserListener<T>): void {
    this.listeners.add(listener);
  }

  removeListener(listener: BrowserListener<T>): void {
    this.listeners.delete(listener);
  }

  emit(value: T): void {
    for (const listener of [...this.listeners]) listener(value);
  }

  async emitAsync(value: T, sender?: unknown): Promise<unknown> {
    let result: unknown;
    for (const listener of [...this.listeners]) {
      result = await (listener as (value: T, sender?: unknown) => unknown)(value, sender);
      if (result !== undefined) break;
    }
    return result;
  }
}

export interface MockNativePort extends NativePort {
  sent: unknown[];
  emitMessage(value: unknown): void;
  emitDisconnect(): void;
}

export interface MockBrowserOptions {
  nativeHostAvailable?: boolean;
  onConnect?: (host: string, port: MockNativePort) => void;
}

export interface MockBrowserApi extends BrowserApi {
  ports: MockNativePort[];
  connectedHosts: string[];
  emitRuntimeMessage(message: unknown, sender?: unknown): Promise<unknown>;
  store: Record<string, unknown>;
}

export function createMockPort(name = 'com.newpgp.bridge'): MockNativePort {
  const onMessage = new MockEvent<unknown>();
  const onDisconnect = new MockEvent<unknown>();
  const sent: unknown[] = [];
  return {
    name,
    sent,
    onMessage,
    onDisconnect,
    postMessage(message) {
      sent.push(message);
    },
    disconnect() {
      // no-op
    },
    emitMessage(value) {
      onMessage.emit(value);
    },
    emitDisconnect() {
      onDisconnect.emit(undefined);
    },
  };
}

export function createMockBrowserApi(options: MockBrowserOptions = {}): MockBrowserApi {
  const store: Record<string, unknown> = {};
  const ports: MockNativePort[] = [];
  const connectedHosts: string[] = [];
  const onMessage = new MockEvent<unknown>();

  const local: BrowserStorageArea = {
    async get(keys) {
      if (keys === undefined || keys === null) return { ...store };
      const list = typeof keys === 'string' ? [keys] : keys;
      const result: Record<string, unknown> = {};
      for (const key of list) {
        if (key in store) result[key] = store[key];
      }
      return result;
    },
    async set(items) {
      Object.assign(store, items);
    },
    async remove(keys) {
      const list = typeof keys === 'string' ? [keys] : keys;
      for (const key of list) delete store[key];
    },
  };

  return {
    ports,
    connectedHosts,
    store,
    emitRuntimeMessage(message, sender) {
      return onMessage.emitAsync(message, sender);
    },
    runtime: {
      id: 'mock-extension',
      onMessage,
      connectNative(host) {
        connectedHosts.push(host);
        if (options.nativeHostAvailable === false) {
          throw new Error(`Specified native messaging host ${host} not found.`);
        }
        const port = createMockPort(host);
        ports.push(port);
        options.onConnect?.(host, port);
        return port;
      },
      async sendMessage(message) {
        return onMessage.emitAsync(message);
      },
      getURL(path) {
        return `chrome-extension://mock/${path}`;
      },
    },
    storage: { local },
    tabs: {
      async query() {
        return [];
      },
      async sendMessage() {
        return undefined;
      },
    },
  };
}
