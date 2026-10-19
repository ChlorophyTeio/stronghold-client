import 'package:flutter_test/flutter_test.dart';
import 'package:stronghold_client/resource_pack.dart';

void main() {
  final server = Uri.parse('https://test.example:8000/');
  final pack = ResourcePack({
    '/assets/avatar.png': {
      'asset': 'assets/game/assets/avatar.png',
      'bytes': 3,
      'hashes': ['0123456789abcdef', 'fedcba9876543210'],
    },
    '/assets/audio/bgm/theme.mp3': {
      'asset': 'assets/game/assets/audio/bgm/theme.mp3',
      'bytes': 7,
      'hashes': ['0123456789abcdef'],
    },
  });
  test('exact content and model hashes resolve packaged art', () {
    for (final hash in ['0123456789abcdef', 'fedcba9876543210']) {
      expect(pack.resolve(server.resolve('/assets/~$hash/avatar.png'), server),
          isNotNull);
    }
  });
  test('stale, plain, foreign and non-art requests stay on the network', () {
    for (final url in [
      'https://test.example:8000/assets/~aaaaaaaaaaaaaaaa/avatar.png',
      'https://test.example:8000/assets/avatar.png',
      'https://other.example:8000/assets/~0123456789abcdef/avatar.png',
      'https://test.example:8000/api/auth/me',
      'https://test.example:8000/sim/~0123456789abcdef/avatar.png',
      'https://test.example:8000/assets/~0123456789abcdef/avatar.png?v=1',
      'https://test.example:8000/assets/~0123456789abcdef/%2e%2e/avatar.png',
    ]) {
      expect(pack.resolve(Uri.parse(url), server), isNull, reason: url);
    }
  });
  test('extensionless media resolves the correct packaged audio', () {
    expect(
        pack.resolve(server.resolve('/media/~0123456789abcdef/bgm/theme'),
            server)?['bytes'],
        7);
  });
  test('ranges support bounded, open and suffix requests', () {
    expect(ByteRange.parse('bytes=2-5', 10)?.start, 2);
    expect(ByteRange.parse('bytes=2-5', 10)?.end, 5);
    expect(ByteRange.parse('bytes=8-', 10)?.end, 9);
    expect(ByteRange.parse('bytes=8-100', 10)?.end, 9);
    expect(ByteRange.parse('bytes=-3', 10)?.start, 7);
    expect(ByteRange.parse('bytes=-30', 10)?.start, 0);
  });
  test('invalid and multipart ranges fall back to server', () {
    for (final value in [
      'bytes=10-',
      'bytes=5-2',
      'bytes=-0',
      'bytes=-',
      'bytes=0-1,3-4',
      'garbage',
      'bytes=9999999999999999999999-'
    ]) {
      expect(ByteRange.parse(value, 10), isNull, reason: value);
    }
  });
  test('server settings require an HTTPS origin', () {
    expect(parseServer(' https://test.example:8000 ').toString(),
        'https://test.example:8000/');
    for (final value in [
      'http://test.example',
      'file:///x',
      'https://user:pass@test.example',
      'https://test.example/api',
      'https://test.example/?secret=x',
      'https://test.example/#x'
    ]) {
      expect(() => parseServer(value), throwsFormatException);
    }
  });
}
