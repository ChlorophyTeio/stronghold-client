#!/usr/bin/env bash
set -euo pipefail
CLIENT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$CLIENT_ROOT"
python3 tool/prepare-update-config.py
python3 tool/test-updates.py
./tool/flutterw pub get
node tool/export_resources.mjs
node tool/verify_resources.mjs
./tool/flutterw analyze
./tool/flutterw test
nice -n 10 ./tool/flutterw build apk --release --target-platform android-arm,android-arm64,android-x64
python3 tool/publish-apk.py build/app/outputs/flutter-apk/app-release.apk
