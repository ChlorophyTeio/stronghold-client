#!/usr/bin/env python3
"""Generate signed offline fixtures in ignored androidTest assets (never shipped)."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import types
root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('builder', root/'tool/build-update.py')
builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
out = root/'android/app/src/androidTest/assets/update-fixtures'
if out.exists(): shutil.rmtree(out)
with tempfile.TemporaryDirectory() as d:
    r=Path(d); (r/'assets').mkdir()
    for sequence, content in [(1,b'fixture-version-one'), (2,b'fixture-version-two')]:
        file=r/'assets/test.png'; file.write_bytes(content)
        digest=builder.sha(file)
        entry=dict(asset='assets/test.png', sha256=digest, bytes=len(content), hashes=[digest[:16]])
        index=r/'index.json'; index.write_text(json.dumps(dict(entries={'/assets/__update_test__.png':entry})))
        args=types.SimpleNamespace(index=index, base_index=None, asset_root=r, key=root/'.toolchain/update-signing/private.pem', output=out/str(sequence), base_url='https://github.com/test/offline/releases/download/test',sequence=sequence,version='offline-test-'+str(sequence),notes='Offline test fixture; never publish',apk=None)
        print(builder.build(args))
    entries={}
    for number in range(8):
        file=r/'assets'/f'batch-{number}.png'
        file.write_bytes((f'parallel-fixture-{number}-'.encode())*4096)
        digest=builder.sha(file)
        entries[f'/assets/batch-{number}.png']=dict(asset=f'assets/batch-{number}.png',sha256=digest,bytes=file.stat().st_size,hashes=[digest[:16]])
    index=r/'batch-index.json';index.write_text(json.dumps(dict(entries=entries)))
    args=types.SimpleNamespace(index=index,base_index=None,asset_root=r,key=root/'.toolchain/update-signing/private.pem',output=out/'3',base_url='https://github.com/test/offline/releases/download/test',sequence=3,version='offline-batch-test',notes='Offline fixture; never publish',apk=None)
    print(builder.build(args))

# Exercise the full catalogue parser without shipping copies of real art in tests.
import base64
import subprocess
index=json.loads((root/'updates/resource-index.json').read_text())
entries={k:v for k,v in index['entries'].items() if not k.endswith('.obj')}
payload=builder.encode(dict(schema=1,packageId='top.ppparty.stronghold_client',channel='preview',sequence=1,resourceVersion='catalogue-parser-fixture',minClientCode=6,notes='Parser fixture only',entries=entries,packs=[]))
with tempfile.TemporaryDirectory() as d:
    p=Path(d);(p/'payload').write_bytes(payload)
    subprocess.run(['openssl','dgst','-sha256','-sign',str(root/'.toolchain/update-signing/private.pem'),'-out',str(p/'signature'),str(p/'payload')],check=True)
    target=out/'baseline';target.mkdir()
    (target/'update-preview.json').write_bytes(builder.encode(dict(payload=base64.b64encode(payload).decode(),signature=base64.b64encode((p/'signature').read_bytes()).decode())))
