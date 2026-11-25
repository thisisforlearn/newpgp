// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
import { beforeEach } from 'vitest';
import { createMockBrowserApi } from './mocks/browser';

beforeEach(() => {
  const api = createMockBrowserApi();
  (globalThis as unknown as { chrome: unknown }).chrome = api;
  (globalThis as unknown as { browser: unknown }).browser = api;
  document.body.replaceChildren();
});
