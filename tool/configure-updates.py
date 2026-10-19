#!/usr/bin/env python3
"""Pin a GitHub repository before building. Does not contact or create repositories."""
import argparse
import json
from pathlib import Path
import re
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('repository', help='owner/repo')
a = p.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', a.repository):
    p.error('Expected owner/repo')
r = Path(__file__).resolve().parents[1]
value = json.dumps(dict(repository=a.repository, channel='preview', manifestAsset='update-preview.json'), indent=2) + '\n'
for path in ['updates/source.json', 'android/app/src/main/assets/update-source.json']:
    (r / path).write_text(value)
print('Configured: ' + a.repository)
