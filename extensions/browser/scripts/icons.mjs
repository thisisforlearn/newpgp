// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Dependency-free PNG icon generator.
 *
 * Chrome does not accept SVG files in the manifest `icons` key, so the build
 * rasterises the same simple shield artwork to 16/32/48/128 PNG files. No
 * native image libraries or external services are involved: this is a small,
 * deterministic software rasteriser plus a minimal PNG writer (zlib is built
 * into Node).
 */

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE = 128;
const SAMPLES = 4; // 4x4 supersampling for smooth edges at small sizes
const BRAND = { r: 47, g: 70, b: 189 };
const WHITE = { r: 255, g: 255, b: 255 };

const OUTER_SHIELD = [
  [64, 16], [100, 30], [100, 58], [98, 70], [92, 82], [82, 93],
  [70, 100], [64, 103], [58, 100], [46, 93], [36, 82], [30, 70],
  [28, 58], [28, 30],
];

const INNER_SHIELD = [
  [64, 25], [92, 37], [92, 58], [90, 68], [85, 78], [77, 87],
  [68, 93], [64, 95], [60, 93], [51, 87], [43, 78], [38, 68],
  [36, 58], [36, 37],
];

function insideRoundedRect(x, y) {
  const qx = Math.abs(x - 64) - (64 - 28);
  const qy = Math.abs(y - 64) - (64 - 28);
  const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
  const inside = Math.min(Math.max(qx, qy), 0);
  return outside + inside - 28 <= 0;
}

function insidePolygon(x, y, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const [xi, yi] = points[i];
    const [xj, yj] = points[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

function insideCircle(x, y, cx, cy, r) {
  return (x - cx) ** 2 + (y - cy) ** 2 <= r ** 2;
}

function sample(x, y) {
  if (!insideRoundedRect(x, y)) return { r: 0, g: 0, b: 0, a: 0 };
  let color = BRAND;
  if (insidePolygon(x, y, OUTER_SHIELD)) {
    color = WHITE;
    if (insidePolygon(x, y, INNER_SHIELD)) color = BRAND;
  }
  // Keyhole: round head, tapered stem, rounded foot.
  if (
    insideCircle(x, y, 64, 58, 10) ||
    (x >= 60 && x <= 68 && y >= 66 && y <= 80) ||
    insideCircle(x, y, 64, 80, 4)
  ) {
    color = WHITE;
  }
  return { ...color, a: 255 };
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([length, body, crc]);
}

/** Renders a square RGBA PNG icon of the requested size. */
export function renderIcon(size) {
  if (!Number.isInteger(size) || size < 1) throw new Error('Icon size must be a positive integer');
  const raw = Buffer.alloc(size * (1 + size * 4));
  const step = BASE / size;
  for (let py = 0; py < size; py += 1) {
    const rowStart = py * (1 + size * 4);
    raw[rowStart] = 0; // PNG filter type: none
    for (let px = 0; px < size; px += 1) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (let sy = 0; sy < SAMPLES; sy += 1) {
        for (let sx = 0; sx < SAMPLES; sx += 1) {
          const s = sample((px + (sx + 0.5) / SAMPLES) * step, (py + (sy + 0.5) / SAMPLES) * step);
          r += s.r * s.a;
          g += s.g * s.a;
          b += s.b * s.a;
          a += s.a;
        }
      }
      const offset = rowStart + 1 + px * 4;
      if (a === 0) {
        raw[offset] = raw[offset + 1] = raw[offset + 2] = raw[offset + 3] = 0;
      } else {
        raw[offset] = Math.round(r / a);
        raw[offset + 1] = Math.round(g / a);
        raw[offset + 2] = Math.round(b / a);
        raw[offset + 3] = Math.round(a / (SAMPLES * SAMPLES));
      }
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Writes the standard Chrome/Edge/Firefox icon set into `directory`. */
export function writeIcons(directory) {
  mkdirSync(directory, { recursive: true });
  const sizes = [16, 32, 48, 128];
  for (const size of sizes) {
    writeFileSync(join(directory, `icon${size}.png`), renderIcon(size));
  }
  return sizes;
}
