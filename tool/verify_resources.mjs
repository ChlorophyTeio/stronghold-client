import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(await fs.readFile(path.join(root, 'assets/game/index.json'), 'utf8'));
const unique = new Map(Object.values(manifest.entries).map((x) => [x.asset, x]));
let bytes = 0;
let modelFiles = 0;
for (const [asset, entry] of unique) {
  if (!asset.startsWith('assets/game/assets/') && !asset.startsWith('assets/game/fonts/')) {
    throw new Error(`Unexpected bundled path: ${asset}`);
  }
  const data = await fs.readFile(path.join(root, asset));
  const hash = createHash('sha256').update(data).digest('hex');
  if (data.length !== entry.bytes || hash !== entry.sha256 || !entry.hashes.includes(hash.slice(0,16))) {
    throw new Error(`Resource integrity failure: ${asset}`);
  }
  bytes += data.length;
  if (entry.hashes.length > 1) modelFiles++;
}
if (bytes !== manifest.totalBytes) throw new Error('Manifest byte total mismatch');
console.log(JSON.stringify({ verifiedFiles: unique.size, bytes, modelFiles, ok: true }, null, 2));
