// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/** Tiny DOM builders. Untrusted strings are always assigned to textContent. */

export interface ElementOptions {
  className?: string;
  text?: string;
  attrs?: Record<string, string>;
}

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  options: ElementOptions = {},
  children: Node[] = [],
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (options.className) node.className = options.className;
  if (options.text !== undefined) node.textContent = options.text;
  for (const [key, value] of Object.entries(options.attrs ?? {})) node.setAttribute(key, value);
  for (const child of children) node.append(child);
  return node;
}

export function requireElement<T extends Element>(selector: string, scope: ParentNode = document): T {
  const found = scope.querySelector<T>(selector);
  if (!found) throw new Error(`NewPGP UI is missing an element: ${selector}`);
  return found;
}
