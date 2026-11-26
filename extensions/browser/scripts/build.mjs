import { build } from 'esbuild';
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeIcons } from './icons.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');

const entries = {
  'background/service-worker': 'src/background/service-worker.ts',
  'content/gmail': 'src/content/gmail.ts',
  'content/outlook': 'src/content/outlook.ts',
  'ui/popup': 'src/ui/popup.ts',
  'ui/options': 'src/ui/options.ts',
};

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });

for (const [outfile, entry] of Object.entries(entries)) {
  await build({
    entryPoints: [join(root, entry)],
    outfile: join(dist, `${outfile}.js`),
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'es2022',
    legalComments: 'none',
  });
}

const copies = [
  ['manifest.json', 'manifest.json'],
  ['icon.svg', 'icon.svg'],
  ['src/ui/popup.html', 'popup.html'],
  ['src/ui/options.html', 'options.html'],
  ['src/ui/styles.css', 'ui/styles.css'],
  ['src/ui/content.css', 'ui/content.css'],
];

for (const [from, to] of copies) {
  const target = join(dist, to);
  mkdirSync(dirname(target), { recursive: true });
  cpSync(join(root, from), target);
}

// Chrome does not accept SVG in the manifest `icons` key. Rasterise the same
// shield artwork to real PNGs so the unpacked extension loads in every target.
writeIcons(dist);

console.log(`NewPGP extension built into ${dist}`);
