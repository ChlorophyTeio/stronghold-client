#!/usr/bin/env python3
"""Wrap a verified APK for transfer; stream-verify the ZIP entry before publishing."""
import hashlib
import os
from pathlib import Path
import re
import sys
import tempfile
import zipfile
root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1]).resolve()
version = re.search(r'^version:\s*([\d.]+)', (root/'pubspec.yaml').read_text(), re.M).group(1)
with apk.open('rb') as reader: digest = hashlib.file_digest(reader,'sha256').hexdigest()
name = f'stronghold-client-{version}-verified-{digest[:8]}.zip'
inner = f'stronghold-client-{version}-verified.apk'
with tempfile.TemporaryDirectory(prefix='.publish-apk-', dir=root) as directory:
    target = Path(directory)/name
    with zipfile.ZipFile(target,'w',zipfile.ZIP_STORED,allowZip64=True) as z:
        z.write(apk,inner)
        z.writestr('SHA256SUMS.txt',f'{digest}  {inner}\n')
        z.writestr('安装说明.txt','请等待下载完成，完整解压 ZIP 后安装 APK。不要安装正在传输的文件。\n如仍提示内容摘要不匹配，请对照 SHA256SUMS.txt 检查手机上的 APK 哈希。\n')
    with zipfile.ZipFile(target) as z:
        with z.open(inner) as reader:
            if hashlib.file_digest(reader,'sha256').hexdigest() != digest:
                raise RuntimeError('ZIP APK hash mismatch')
    with target.open('rb') as reader: archive_hash = hashlib.file_digest(reader,'sha256').hexdigest()
    os.replace(target,root/name)
    (root/(name+'.sha256')).write_text(f'{archive_hash}  {name}\n')
print(name)
