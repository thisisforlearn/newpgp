// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
export interface PackageOptions {
  distDir: string;
  outDir?: string;
  name?: string;
}

export interface PackageResult {
  zipPath: string;
  xpiPath: string;
  version: string;
  files: string[];
  bytes: number;
}

export declare function createZip(files: Array<{ name: string; data: Uint8Array }>): Uint8Array;
export declare function packageExtension(options: PackageOptions): PackageResult;
