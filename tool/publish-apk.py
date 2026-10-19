#!/usr/bin/env python3
"""Verify the copied delivery APK before atomically publishing its public name."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
source = Path(sys.argv[1]).resolve()
version = re.search(r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)',
                    (root / 'pubspec.yaml').read_text(), re.M).group(1)
destination = root / f'stronghold-client-{version}-preview.apk'

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

expected = digest(source)
with tempfile.TemporaryDirectory(prefix='.publish-apk-', dir=root) as folder:
    staged = Path(folder) / destination.name
    with source.open('rb') as reader, staged.open('wb') as writer:
        shutil.copyfileobj(reader, writer, 1024 * 1024)
        writer.flush()
        os.fsync(writer.fileno())
    if digest(staged) != expected:
        raise RuntimeError('APK changed during delivery copy; publication aborted')
    subprocess.run([sys.executable, str(root / 'tool/verify-apk.py'), str(staged)], check=True)
    subprocess.run([
        str(root / '.toolchain/jdk/bin/java'), '-jar',
        str(root / '.toolchain/android-sdk/build-tools/36.0.0/lib/apksigner.jar'),
        'verify', '--verbose', '--print-certs', str(staged),
    ], check=True)
    checksum = Path(folder) / (destination.name + '.sha256')
    checksum.write_text(f'{expected}  {destination.name}\n')
    os.replace(staged, destination)
    os.replace(checksum, root / checksum.name)

if digest(destination) != expected:
    raise RuntimeError('Published APK hash differs from verified delivery copy')
print(json.dumps({'apk': str(destination), 'sha256': expected, 'ok': True}))
