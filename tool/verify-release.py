#!/usr/bin/env python3
"""Verify a public GitHub release, its signed manifest and optional full APK download."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != 'https':
            raise ValueError('Refused non-HTTPS release redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

opener = urllib.request.build_opener(HttpsRedirects())

def open_url(url):
    if urllib.parse.urlsplit(url).scheme != 'https':
        raise ValueError('HTTPS required')
    request = urllib.request.Request(url, headers={'User-Agent': 'Stronghold-Release-Verifier/1', 'Accept-Encoding': 'identity'})
    return opener.open(request, timeout=45)

def read(url, limit):
    with open_url(url) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Release metadata exceeded size limit')
    return data

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repository', default=json.loads((ROOT/'updates/source.json').read_text())['repository'])
    p.add_argument('--tag', required=True)
    p.add_argument('--download-apk', action='store_true', help='Stream the entire public APK and verify its hash')
    p.add_argument('--local-apk', type=Path, help='Also compare the verified local delivery APK')
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    import re
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        p.error('Expected owner/repo')
    tag = urllib.parse.quote(args.tag, safe='')
    release = json.loads(read(f'https://api.github.com/repos/{args.repository}/releases/tags/{tag}', 2*1024*1024))
    assert release['draft'] is False and release['tag_name'] == args.tag, 'Release is not public'
    assets = {a['name']: a for a in release['assets']}
    manifest_asset = assets['update-preview.json']
    manifest = read(manifest_asset['browser_download_url'], 24*1024*1024)
    manifest_hash = hashlib.sha256(manifest).hexdigest()
    assert manifest_asset['digest'] == 'sha256:'+manifest_hash, 'GitHub manifest digest mismatch'
    envelope = json.loads(manifest)
    payload = base64.b64decode(envelope['payload'], validate=True)
    signature = base64.b64decode(envelope['signature'], validate=True)
    with tempfile.TemporaryDirectory(prefix='stronghold-release-verify-') as directory:
        temp = Path(directory)
        (temp/'payload').write_bytes(payload); (temp/'signature').write_bytes(signature)
        subprocess.run(['openssl','dgst','-sha256','-verify', str(ROOT/'android/app/src/main/assets/update-public.pem'), '-signature',str(temp/'signature'),str(temp/'payload')], check=True, capture_output=True)
    signed = json.loads(payload)
    assert signed['schema'] == 1 and signed['packageId'] == 'top.ppparty.stronghold_client' and signed['channel'] == 'preview', 'Wrong application/channel'
    apk = signed.get('apk')
    report = dict(repository=args.repository, tag=args.tag, url=release['html_url'], signedManifest=True,
                  manifestSha256=manifest_hash, resourceVersion=signed['resourceVersion'], sequence=signed['sequence'])
    if apk:
        prefix = f'https://github.com/{args.repository}/releases/download/'
        assert apk['url'].startswith(prefix), 'APK URL does not belong to this repository'
        apk_tag, name = apk['url'].removeprefix(prefix).split('/', 1)
        assert apk_tag and '/' not in name and name, 'Unexpected APK asset path'
        apk_assets = assets
        if apk_tag != tag:
            apk_release = json.loads(read(f'https://api.github.com/repos/{args.repository}/releases/tags/{apk_tag}', 2*1024*1024))
            assert not apk_release['draft'], 'Referenced APK is not public'
            apk_assets = {a['name']: a for a in apk_release['assets']}
        asset = apk_assets[name]
        assert asset['state'] == 'uploaded' and asset['size'] == apk['bytes'], 'Remote APK is incomplete'
        assert asset['digest'] == 'sha256:'+apk['sha256'], 'GitHub APK digest mismatch'
        checksum = read(apk_assets[name+'.sha256']['browser_download_url'], 4096).decode().split()
        assert checksum == [apk['sha256'], name], 'APK checksum attachment mismatch'
        if args.local_apk:
            with args.local_apk.open('rb') as stream:
                assert hashlib.file_digest(stream, 'sha256').hexdigest() == apk['sha256'], 'Local APK mismatch'
        if args.download_apk:
            digest = hashlib.sha256(); received = 0; checkpoint = time.monotonic()
            with open_url(apk['url']) as response:
                print('Public APK response: '+json.dumps({key:response.headers.get(key) for key in ['Content-Length','Content-Range','Content-Encoding','Transfer-Encoding']}), flush=True)
                while chunk := response.read(1024*1024):
                    received += len(chunk); digest.update(chunk)
                    assert received <= apk['bytes'], 'Downloaded APK exceeds declared length'
                    if time.monotonic()-checkpoint > 20:
                        print(f'Public APK verification: {received/1048576:.1f}/{apk["bytes"]/1048576:.1f} MiB', flush=True)
                        checkpoint = time.monotonic()
            assert received == apk['bytes'] and digest.hexdigest() == apk['sha256'], f'Downloaded APK mismatch: bytes={received}/{apk["bytes"]}, sha256={digest.hexdigest()}, expected={apk["sha256"]}'
            report['publicApkDownloadVerified'] = True
        report.update(apkSha256=apk['sha256'], apkBytes=apk['bytes'], apkVersionCode=apk['versionCode'])
    report['ok'] = True
    if args.output: args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
