#!/usr/bin/env python3
"""Prepare lightweight bootstrap assets and update trust. Never connects to GitHub."""
import json
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
bootstrap = root / 'assets/game/index.json'
bootstrap.parent.mkdir(parents=True, exist_ok=True)
bootstrap.write_text('{"version":1,"totalBytes":0,"entries":{}}')
source = root / 'updates/source.json'
config = json.loads(source.read_text())
repo = config.get('repository', '')
if repo and not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
    raise ValueError('Invalid GitHub repository')
if config.get('channel') != 'preview' or config.get('manifestAsset') != 'update-preview.json':
    raise ValueError('Unsupported update channel')
public_key = root / 'android/app/src/main/assets/update-public.pem'
if not public_key.is_file():
    raise ValueError('Missing embedded update public key; do not regenerate a deployed key')
output = root / 'android/app/src/main/assets/update-source.json'
if not output.is_file() or output.read_bytes() != source.read_bytes():
    output.write_bytes(source.read_bytes())
print('Update source: ' + (repo or 'unconfigured (game remains available)'))
