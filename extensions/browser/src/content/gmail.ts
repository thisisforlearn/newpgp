// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Gmail entry point. Bundled together with the shared content modules. */

import { getBrowserApi } from '../platform/browser';
import { ensureContentStyles, gmailSelectors, installComposeInterceptor } from './common';
import { installIncomingDecrypt } from './incoming';

function start(): void {
  const api = getBrowserApi();
  ensureContentStyles(api);
  installComposeInterceptor({ api, selectors: gmailSelectors() });
  installIncomingDecrypt(api);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', start, { once: true });
} else {
  start();
}
