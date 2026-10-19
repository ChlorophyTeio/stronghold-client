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

args=types.SimpleNamespace(index=root/'assets/game/index.json', base_index=root/'updates/baselines/0.2.0-index.json.gz', asset_root=root, key=root/'.toolchain/update-signing/private.pem', output=out/'baseline', base_url='https://github.com/test/offline/releases/download/baseline',sequence=1,version='baseline-test',notes='Offline baseline test; never publish',apk=None)
print(builder.build(args))
