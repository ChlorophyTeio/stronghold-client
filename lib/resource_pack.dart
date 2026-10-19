import 'dart:convert';

import 'package:flutter/services.dart';

/// Only explicit, server-issued content hashes can select packaged resources.
/// HTML, scripts, simulation, data, API calls and plain URLs stay on the server.
class ResourcePack {
  ResourcePack(this.entries, {this.totalBytes = 0});

  final Map<String, dynamic> entries;
  final int totalBytes;
  int hits = 0;
  int bytesSaved = 0;
  int misses = 0;

  static final _hashed = RegExp(
    r'^/(assets|fonts|media)/~([0-9a-f]{16})/(.+)$',
  );
  static const _audioExtensions = [
    '.mp3',
    '.m4a',
    '.aac',
    '.ogg',
    '.oga',
    '.opus',
    '.wav',
  ];

  static Future<ResourcePack> load() async {
    final raw = jsonDecode(
      await rootBundle.loadString('assets/game/index.json'),
    ) as Map<String, dynamic>;
    return ResourcePack(
      raw['entries'] as Map<String, dynamic>,
      totalBytes: raw['totalBytes'] as int,
    );
  }

  Map<String, dynamic>? resolve(Uri url, Uri server) {
    if (url.scheme != 'https' ||
        url.origin != server.origin ||
        url.hasQuery ||
        url.hasFragment) {
      return null;
    }
    final match = _hashed.firstMatch(url.path);
    if (match == null) return null;
    String rest;
    try {
      rest = Uri.decodeComponent(match[3]!);
    } on FormatException {
      return null;
    }
    if (rest.split('/').any((s) => s.isEmpty || s == '.' || s == '..') ||
        rest.contains('\\') ||
        rest.contains('\u0000')) {
      return null;
    }
    final root = match[1]!;
    final paths = root == 'media'
        ? _audioExtensions.map((ext) => '/assets/audio/$rest$ext')
        : ['/$root/$rest'];
    for (final path in paths) {
      final entry = entries[path];
      if (entry is Map<String, dynamic> &&
          (entry['hashes'] as List).contains(match[2])) {
        return entry;
      }
    }
    return null;
  }

  Future<Uint8List> read(Map<String, dynamic> entry) async {
    final data = await rootBundle.load(entry['asset'] as String);
    return data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
  }
}

/// HTTP single-range semantics, including suffix ranges. Multi-range is left
/// to the original server rather than fabricating a multipart response.
class ByteRange {
  const ByteRange(this.start, this.end);
  final int start;
  final int end;

  static ByteRange? parse(String value, int length) {
    final match = RegExp(r'^bytes=(\d*)-(\d*)$').firstMatch(value);
    if (match == null || length <= 0) return null;
    final left = match[1]!;
    final right = match[2]!;
    if (left.isEmpty && right.isEmpty) return null;
    if (left.isEmpty) {
      final suffix = int.tryParse(right);
      if (suffix == null || suffix <= 0) return null;
      return ByteRange(suffix >= length ? 0 : length - suffix, length - 1);
    }
    final start = int.tryParse(left);
    final end = right.isEmpty ? length - 1 : int.tryParse(right);
    if (start == null || end == null || start >= length || start > end) {
      return null;
    }
    return ByteRange(start, end >= length ? length - 1 : end);
  }
}

Uri parseServer(String value) {
  final uri = Uri.tryParse(value.trim());
  if (uri == null ||
      uri.scheme != 'https' ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      (uri.path.isNotEmpty && uri.path != '/')) {
    throw const FormatException('请输入 HTTPS 服务器地址，不含路径、账号或参数');
  }
  return Uri.parse('${uri.origin}/');
}
