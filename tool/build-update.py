#!/usr/bin/env python3
"""Create signed, cumulative resource deltas from immutable material snapshots.
Never uploads. Private key stays outside source control. All URLs are HTTPS.
"""
import argparse
import base64
import hashlib
import gzip
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEX = re.compile(r'[0-9a-f]{64}')

def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def encode(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'), sort_keys=True).encode()

def build(args):
    public_key = getattr(args, 'public_key', None)
    if public_key:
        actual = subprocess.check_output(['openssl', 'pkey', '-in', str(args.key), '-pubout', '-outform', 'DER'])
        expected = subprocess.check_output(['openssl', 'pkey', '-pubin', '-in', str(public_key), '-outform', 'DER'])
        if actual != expected:
            raise ValueError('Signing key does not match the APK update public key')
    source = json.loads(args.index.read_text())
    baseline_bytes = (gzip.decompress(args.base_index.read_bytes()) if args.base_index.suffix == '.gz' else args.base_index.read_bytes()) if args.base_index else b'{"entries":{}}'
    baseline = json.loads(baseline_bytes)['entries']
    available = {e['sha256'] for e in baseline.values()}
    entries = source['entries']
    unique = {}
    for path, entry in entries.items():
        if not path.startswith(('/assets/', '/fonts/')) or '..' in path.split('/'):
            raise ValueError('Unsafe art path')
        extension = path.rsplit('.', 1)[-1].lower()
        if extension not in {'webp','png','jpg','jpeg','gif','avif','svg','mp3','ogg','wav','m4a','aac','flac','ttf','otf','woff','woff2','skel','atlas','json','bin','ktx','ktx2'} and path not in {'/assets/ui/statistics/fonts.css','/fonts/fonts.css'}:
            raise ValueError('Non-art update path: ' + path)
        relative = Path(entry['asset'])
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Unsafe local asset path')
        digest = entry['sha256']
        if not HEX.fullmatch(digest) or entry['bytes'] < 0:
            raise ValueError('Invalid resource')
        # Resolve through the pinned links, and verify every byte before publishing.
        file = args.asset_root / entry['asset']
        if file.stat().st_size != entry['bytes'] or sha(file) != digest:
            raise ValueError('Resource changed: ' + path)
        unique[digest] = (file, entry['bytes'])
    args.output.mkdir(parents=True, exist_ok=True)
    buckets = {}
    for digest, (file, size) in sorted(unique.items()):
        if digest not in available:
            buckets.setdefault(digest[:2], []).append((digest, file, size))
    packs = []
    # Bucket membership is content based; split at 16 MiB (oversize single files allowed).
    for bucket, members in sorted(buckets.items()):
        groups, group, count = [], [], 0
        for item in members:
            if group and count + item[2] > 16 * 1024 * 1024:
                groups.append(group); group, count = [], 0
            group.append(item); count += item[2]
        if group: groups.append(group)
        for number, group in enumerate(groups):
            temporary = args.output / f'.pack-{bucket}-{number}.zip'
            with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_STORED, allowZip64=True) as z:
                for digest, file, size in group:
                    info = zipfile.ZipInfo(digest, (2026, 1, 1, 0, 0, 0))
                    info.external_attr = 0o100644 << 16
                    with file.open('rb') as inp, z.open(info, 'w', force_zip64=True) as out:
                        import shutil
                        shutil.copyfileobj(inp, out, 1024 * 1024)
            digest = sha(temporary)
            name = f'resources-{digest}.zip'
            final = args.output / name
            temporary.replace(final)
            packs.append(dict(url=args.base_url.rstrip('/') + '/' + name,
                              sha256=digest, bytes=final.stat().st_size,
                              files=[i[0] for i in group]))
    manifest = dict(schema=1, packageId='top.ppparty.stronghold_client', channel='preview',
                    sequence=args.sequence, resourceVersion=args.version, minClientCode=6,
                    notes=args.notes, entries=entries, packs=packs)
    if args.apk:
        if not args.apk_version_code or not args.apk_certificate:
            raise ValueError('APK requires version code and certificate SHA-256')
        manifest['apk'] = dict(url=args.base_url.rstrip('/') + '/' + args.apk.name,
                              sha256=sha(args.apk), bytes=args.apk.stat().st_size,
                              versionCode=args.apk_version_code,
                              certificateSha256=args.apk_certificate.lower().replace(':', ''))
    payload = encode(manifest)
    with tempfile.TemporaryDirectory() as directory:
        inp, sig = Path(directory) / 'payload', Path(directory) / 'signature'
        inp.write_bytes(payload)
        subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(args.key),
                        '-out', str(sig), str(inp)], check=True)
        envelope = dict(payload=base64.b64encode(payload).decode(),
                        signature=base64.b64encode(sig.read_bytes()).decode())
    output = args.output / 'update-preview.json'
    output.write_bytes(encode(envelope))
    return dict(manifest=str(output), resources=len(entries), packs=len(packs),
                downloadBytes=sum(p['bytes'] for p in packs), sha256=sha(output))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--index', type=Path, default=ROOT / 'assets/game/index.json')
    p.add_argument('--base-index', type=Path, default=ROOT / 'updates/baselines/0.2.0-index.json.gz', help='Index embedded in oldest supported APK; omit for full pack')
    p.add_argument('--full', action='store_true', help='Generate a full resource pack instead of the APK baseline delta')
    p.add_argument('--asset-root', type=Path, default=ROOT)
    p.add_argument('--key', type=Path, default=ROOT / '.toolchain/update-signing/private.pem')
    p.add_argument('--public-key', type=Path, default=ROOT / 'android/app/src/main/assets/update-public.pem')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--base-url', required=True, help='https://github.com/OWNER/REPO/releases/download/TAG')
    p.add_argument('--sequence', type=int, required=True)
    p.add_argument('--version', required=True)
    p.add_argument('--notes', default='')
    p.add_argument('--apk', type=Path)
    p.add_argument('--apk-version-code', type=int)
    p.add_argument('--apk-certificate')
    args = p.parse_args()
    if args.full: args.base_index = None
    if not args.base_url.startswith('https://') or args.sequence < 1:
        p.error('HTTPS release URL and positive sequence required')
    print(json.dumps(build(args), ensure_ascii=False))

if __name__ == '__main__': main()
