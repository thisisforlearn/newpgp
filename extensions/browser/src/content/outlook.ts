// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Outlook on the web entry point. Bundled with the shared content modules. */

import { getBrowserApi } from '../platform/browser';
import { ensureContentStyles, installComposeInterceptor, outlookSelectors } from './common';
import { installIncomingDecrypt } from './incoming';

function start(): void {
  const api = getBrowserApi();
  ensureContentStyles(api);
  installComposeInterceptor({ api, selectors: outlookSelectors() });
  installIncomingDecrypt(api);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', start, { once: true });
} else {
  start();
}
