#!/usr/bin/env python3
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('updates', Path(__file__).with_name('build-update.py'))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

class Packs(unittest.TestCase):
    def test_delta_signature_and_tamper(self):
        with tempfile.TemporaryDirectory() as d:
            r = Path(d)
            key = r / 'key.pem'
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', str(key)], check=True, capture_output=True)
            subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-out', str(r / 'public.pem')], check=True, capture_output=True)
            (r / 'assets').mkdir()
            entries = {}
            for name, data in [('same.png', b'same'), ('changed.png', b'changed')]:
                (r / 'assets' / name).write_bytes(data)
                digest = hashlib.sha256(data).hexdigest()
                entries['/assets/' + name] = dict(asset='assets/' + name, bytes=len(data), sha256=digest, hashes=[digest[:16], '0' * 16])
            index = r / 'index.json'; index.write_text(json.dumps(dict(entries=entries)))
            base = r / 'base.json'; base.write_text(json.dumps(dict(entries={'/assets/same.png': entries['/assets/same.png']})))
            args = types.SimpleNamespace(index=index, base_index=base, asset_root=r, key=key, output=r/'out', base_url='https://github.com/test/test/releases/download/v1', sequence=1, version='test', notes='', apk=None)
            result = builder.build(args)
            self.assertEqual(result['packs'], 1)
            envelope = json.loads(Path(result['manifest']).read_text())
            payload = base64.b64decode(envelope['payload']); (r/'payload').write_bytes(payload)
            (r/'sig').write_bytes(base64.b64decode(envelope['signature']))
            verify = ['openssl', 'dgst', '-sha256', '-verify', str(r/'public.pem'), '-signature', str(r/'sig'), str(r/'payload')]
            self.assertEqual(subprocess.run(verify, capture_output=True).returncode, 0)
            manifest = json.loads(payload)
            self.assertEqual(manifest['entries']['/assets/changed.png']['hashes'][1], '0'*16)
            pack = manifest['packs'][0]
            archive = args.output / pack['url'].rsplit('/', 1)[-1]
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), pack['sha256'])
            with zipfile.ZipFile(archive) as z:
                self.assertEqual(z.namelist(), [entries['/assets/changed.png']['sha256']])
                self.assertEqual(z.read(z.namelist()[0]), b'changed')
            (r/'payload').write_bytes(payload + b' ')
            self.assertNotEqual(subprocess.run(verify, capture_output=True).returncode, 0)
            # A light APK has no embedded baseline: retain old delta URLs but
            # publish the baseline bytes as well, without duplicate contents.
            delta_manifest = Path(result['manifest'])
            args.base_index = None
            args.reuse_manifest = delta_manifest
            args.public_key = r / 'public.pem'
            args.output = r / 'light'
            args.base_url = 'https://github.com/test/test/releases/download/v2'
            args.sequence = 2
            full = builder.build(args)
            full_manifest = json.loads(base64.b64decode(json.loads(Path(full['manifest']).read_text())['payload']))
            hashes = [h for p in full_manifest['packs'] for h in p['files']]
            self.assertEqual(set(hashes), {e['sha256'] for e in entries.values()})
            self.assertEqual(len(hashes), len(set(hashes)))
            self.assertEqual(full_manifest['packs'][0], pack)
            self.assertEqual(len(list(args.output.glob('resources-*.zip'))), 1)
            tampered = json.loads(delta_manifest.read_text())
            tampered['payload'] = base64.b64encode(b'{}').decode()
            bad_manifest = r/'bad.json'; bad_manifest.write_text(json.dumps(tampered))
            args.reuse_manifest = bad_manifest
            with self.assertRaises(subprocess.CalledProcessError): builder.build(args)
            args.reuse_manifest = delta_manifest
            # A corrupt source cannot produce a signed release.
            (r/'assets/changed.png').write_bytes(b'corrupt')
            with self.assertRaises(ValueError): builder.build(args)

if __name__ == '__main__': unittest.main()
