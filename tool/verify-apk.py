#!/usr/bin/env python3
"""Check the packaged resource inventory; apksigner verifies APK integrity separately."""
import hashlib
import json
import re
import subprocess
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)', (root / 'pubspec.yaml').read_text(), re.M).group(1)
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / f'stronghold-client-{version}-preview.apk'
with zipfile.ZipFile(apk) as bundle:
    manifest = json.loads(bundle.read('assets/flutter_assets/assets/game/index.json'))
    unique = {entry['asset']: entry for entry in manifest['entries'].values()}
    assert not unique and manifest['totalBytes'] == 0, 'Light APK must not bundle game resources'
    assert apk.stat().st_size < 120 * 1024 * 1024, 'Unexpected light APK size'
    for asset, entry in unique.items():
        info = bundle.getinfo('assets/flutter_assets/' + asset)
        assert info.file_size == entry['bytes'], asset
    allowed = {'assets/flutter_assets/' + asset for asset in unique}
    allowed.add('assets/flutter_assets/assets/game/index.json')
    packed = {info.filename for info in bundle.infolist()
              if info.filename.startswith('assets/flutter_assets/assets/game/')}
    assert packed == allowed, 'Pack contains missing or unexpected resources'
    for filename in ('update-source.json', 'update-public.pem'):
        assert bundle.read('assets/' + filename) == (root / 'android/app/src/main/assets' / filename).read_bytes(), 'Update trust/source configuration mismatch'
    assert json.loads(bundle.read('assets/update-source.json')) == json.loads((root/'updates/source.json').read_text())
    assert not any('update-fixtures/' in name or name.endswith(('private.pem', '.keystore', '.jks')) for name in bundle.namelist()), 'Test fixture or private key in APK'
    assert 'AndroidManifest.xml' in bundle.namelist()
    assert 'classes.dex' in bundle.namelist()
    abis = sorted({name.split('/')[1] for name in bundle.namelist()
                   if name.startswith('lib/') and name.endswith('/libflutter.so')})
    assert abis == ['arm64-v8a', 'armeabi-v7a', 'x86_64'], abis
    for abi in abis:
        app_library = bundle.getinfo(f'lib/{abi}/libapp.so')
        assert app_library.file_size > 131072, f'Missing AOT application snapshot: {abi}'
        with bundle.open(app_library) as library:
            assert library.read(4) == b'\x7fELF', f'Invalid AOT application snapshot: {abi}'
    logo = (root / 'assets/branding/container_logo.webp').read_bytes()
    assert bundle.read('assets/flutter_assets/assets/branding/container_logo.webp') == logo
    badging = subprocess.check_output([
        str(root / '.toolchain/android-sdk/build-tools/36.0.0/aapt'), 'dump', 'badging', str(apk),
    ], text=True)
    assert "name='top.ppparty.stronghold_client'" in badging
    assert f"versionName='{version}'" in badging
    code = re.search(r'^version:.*\+(\d+)', (root / "pubspec.yaml").read_text(), re.M).group(1)
    assert f"versionCode='{code}'" in badging
    icon_path = re.search(r"^application:.*icon='([^']+)'", badging, re.M).group(1)
    assert bundle.read(icon_path) == logo, 'Android launcher icon differs from container logo'

with apk.open('rb') as stream:
    checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
print(json.dumps({
    'apk': str(apk.resolve()),
    'apkBytes': apk.stat().st_size,
    'sha256': checksum,
    'resourceFiles': len(unique),
    'resourceBytes': manifest['totalBytes'],
    'abis': abis,
    'ok': True,
}, indent=2))
