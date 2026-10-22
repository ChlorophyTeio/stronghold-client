// Read-only export of public art. No game process or container is started.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.resolve(process.argv[2] || path.join(root, '../spa-test/src'));
const target = path.join(root, 'assets/game');
const lock = JSON.parse(await fs.readFile(path.join(root, 'resource-library.lock.json'), 'utf8'));
if (lock.schema !== 1 || !/^[0-9a-f]{64}$/.test(lock.snapshot)) throw new Error('Invalid resource library lock');
const library = path.resolve(root, lock.library, 'versions', lock.snapshot);
const catalog = JSON.parse(await fs.readFile(path.join(library, 'manifest.json'), 'utf8'));
if (catalog.snapshot !== lock.snapshot) throw new Error('Resource snapshot does not match lock');
const { rewriteManifest } = await import(pathToFileURL(path.join(source, 'server/http/assetViews.js')));
const entries = {};
let totalBytes = 0;
const digest = (data) => createHash('sha256').update(data).digest('hex');
const inventory = Object.fromEntries(Object.keys(catalog.entries)
  .sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)))
  .map((key) => [key, { bytes: catalog.entries[key].bytes, sha256: catalog.entries[key].sha256 }]));
// Match the audit's sorted-key, ASCII-escaped JSON inventory representation.
const canonical = JSON.stringify(inventory).replace(/[\u007f-\uffff]/g,
  (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`);
if (digest(canonical) !== lock.snapshot) throw new Error('Resource catalog content hash does not match lock');

async function walk(dir, rel) {
  for (const item of (await fs.readdir(dir, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
    if (item.name.startsWith('.')) continue;
    const key = `${rel}/${item.name}`;
    const abs = path.join(dir, item.name);
    if (item.isDirectory()) await walk(abs, key);
    else if (item.isFile()) {
      const data = await fs.readFile(abs);
      const sha256 = digest(data);
      const expected = catalog.entries[key];
      if (!expected || expected.bytes !== data.length || expected.sha256 !== sha256) {
        throw new Error(`Shared resource integrity failure: ${key}`);
      }
      entries[`/${key}`] = { asset: `assets/game/${key}`, bytes: data.length, sha256, hashes: [sha256.slice(0,16)] };
      totalBytes += data.length;
    }
  }
}
await fs.mkdir(target, { recursive: true });
for (const name of ['assets', 'fonts']) await walk(path.join(library, name), name);
if (Object.keys(entries).length !== catalog.files || totalBytes !== catalog.totalBytes) {
  throw new Error('Shared resource snapshot inventory mismatch');
}
// Only update project-owned links. Never delete or copy the shared resource tree.
for (const name of ['assets', 'fonts']) {
  const link = path.join(target, name);
  const expected = path.join(library, name);
  const info = await fs.lstat(link).catch((error) => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  if (info && !info.isSymbolicLink()) throw new Error(`Resource path must be a library symlink: ${link}`);
  if (info && await fs.realpath(link) === await fs.realpath(expected)) continue;
  const temporary = `${link}.tmp-${process.pid}`;
  await fs.symlink(path.relative(target, expected), temporary, 'dir');
  await fs.rename(temporary, link);
}
const lower = new Map();
for (const key of Object.keys(entries)) {
  const folded = key.toLowerCase();
  lower.set(folded, lower.has(folded) ? null : key);
}
const entryOf = (rel) => entries[`/${rel}`] || entries[lower.get(`/${rel}`.toLowerCase())];
const index = {
  hashOf: (rel) => entryOf(rel)?.hashes[0] || null,
  modelHash(rels) {
    const files = rels.map(entryOf);
    if (files.some((x) => !x)) return null;
    return digest(files.map((x) => x.hashes[0]).join('\n')).slice(0,16);
  },
};
for (const name of ['assets.json', 'local-assets.json', 'local-fx.json']) {
  let raw;
  try { raw = await fs.readFile(path.join(source, 'data', name), 'utf8'); }
  catch (error) { if (error.code === 'ENOENT') continue; throw error; }
  const { models } = rewriteManifest(JSON.parse(raw), name, index);
  for (const model of models) {
    const hash = index.modelHash(model);
    if (!hash) continue;
    for (const rel of model) {
      const entry = entryOf(rel);
      if (!entry.hashes.includes(hash)) entry.hashes.push(hash);
    }
  }
}
// Match the server's unique case-insensitive lookup without copying art twice.
for (const [key, canonical] of lower) {
  if (canonical && !entries[key]) entries[key] = entries[canonical];
}
// The complete catalogue is release tooling input, never an APK asset.
const cataloguePath = path.join(root, 'updates/resource-index.json');
const temporaryIndex = `${cataloguePath}.tmp-${process.pid}`;
await fs.writeFile(temporaryIndex, JSON.stringify({ version: 1, totalBytes, entries }));
await fs.rename(temporaryIndex, cataloguePath);
await fs.writeFile(path.join(target, 'index.json'), JSON.stringify({ version: 1, totalBytes: 0, entries: {} }));
const dirs = [...new Set(Object.values(entries).map((x) => path.posix.dirname(x.asset) + '/'))].sort();
const pubspec = path.join(root, 'pubspec.yaml');
const yaml = (await fs.readFile(pubspec, 'utf8')).split('  assets:\n')[0];
await fs.writeFile(pubspec, `${yaml}  assets:\n    - assets/branding/\n    - assets/game/index.json\n`);
console.log(JSON.stringify({ files: lower.size, totalBytes, directories: dirs.length, target, library, snapshot: lock.snapshot }, null, 2));
