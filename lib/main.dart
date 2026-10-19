import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import 'resource_pack.dart';

const defaultServer = 'https://spa.ppparty.top:8000';
const gameChannel = MethodChannel('top.ppparty.stronghold/game');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!Platform.isAndroid) {
    await SystemChrome.setPreferredOrientations(DeviceOrientation.values);
  }
  ResourcePack? pack;
  String? packError;
  try {
    pack = await ResourcePack.load();
  } catch (_) {
    packError = '本地资源包读取失败，游戏将使用在线资源';
  }
  runApp(ClientApp(pack: pack, packError: packError));
}

class ClientApp extends StatelessWidget {
  const ClientApp({super.key, this.pack, this.packError});
  final ResourcePack? pack;
  final String? packError;

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: '卫戍协议',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          brightness: Brightness.dark,
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xff65d6bd),
            brightness: Brightness.dark,
          ),
          scaffoldBackgroundColor: const Color(0xff0c1217),
          useMaterial3: true,
          inputDecorationTheme: const InputDecorationTheme(
            border: OutlineInputBorder(),
          ),
        ),
        home: Launcher(pack: pack, packError: packError),
      );
}

class Launcher extends StatefulWidget {
  const Launcher({super.key, this.pack, this.packError});
  final ResourcePack? pack;
  final String? packError;
  @override
  State<Launcher> createState() => _LauncherState();
}

class _LauncherState extends State<Launcher> {
  String? _error;
  bool _opening = false;

  Future<void> _open() async {
    final uri = Uri.parse(defaultServer);
    setState(() {
      _opening = true;
      _error = null;
    });
    try {
      if (Platform.isAndroid) {
        // Android owns the game window and its orientation. Do not wait for a
        // Flutter orientation/surface transition before opening a native view.
        await gameChannel.invokeMethod<void>('openGame');
        return;
      }
      await SystemChrome.setPreferredOrientations([
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
      if (!mounted) return;
      await Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => GamePage(server: uri, pack: widget.pack),
      ));
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = error.message ?? '无法打开游戏窗口');
    } finally {
      if (!Platform.isAndroid) {
        await SystemChrome.setPreferredOrientations(DeviceOrientation.values);
        await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
      }
      if (mounted) setState(() => _opening = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
            child: Center(
                child: SingleChildScrollView(
          padding: const EdgeInsets.all(28),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 620),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Image.asset('assets/branding/container_logo.webp',
                    width: 48, height: 48),
                const SizedBox(width: 14),
                const Expanded(
                    child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('卫戍协议：盟约',
                        style: TextStyle(
                            fontSize: 27, fontWeight: FontWeight.w700)),
                    Text('STRONGHOLD PROTOCOL · CLIENT',
                        style: TextStyle(fontSize: 11, letterSpacing: 1.5)),
                  ],
                )),
              ]),
              const SizedBox(height: 24),
              const Chip(label: Text('Android 预览版 · 0.2.3')),
              const SizedBox(height: 12),
              const Text('本地素材，在线联机',
                  style: TextStyle(fontSize: 22, fontWeight: FontWeight.w600)),
              const SizedBox(height: 10),
              Text(
                  widget.pack == null
                      ? '使用服务器资源'
                      : '已内置 ${(widget.pack!.totalBytes / 1048576).toStringAsFixed(0)} MB '
                          '美术与音频。版本匹配的资源直接从本机读取。',
                  style: const TextStyle(height: 1.6, color: Colors.white70)),
              const SizedBox(height: 24),
              const Text('当前连接：正式服',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              const Text('使用现有游戏账号登录', style: TextStyle(color: Colors.white70)),
              if (_error != null)
                Text(_error!,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error)),
              const SizedBox(height: 22),
              SizedBox(
                  width: double.infinity,
                  height: 54,
                  child: FilledButton.icon(
                    onPressed: _opening ? null : _open,
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: Text(_opening ? '正在打开…' : '进入游戏'),
                  )),
              const SizedBox(height: 12),
              if (Platform.isAndroid)
                TextButton.icon(
                  onPressed: _opening ? null : () async {
                    try {
                      await gameChannel.invokeMethod<void>('openUpdates');
                    } on PlatformException catch (error) {
                      if (mounted) setState(() => _error = error.message);
                    }
                  },
                  icon: const Icon(Icons.system_update),
                  label: const Text('更新设置'),
                ),
              if (widget.packError != null)
                Text(widget.packError!,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error)),
              const SizedBox(height: 20),
              const Text(
                  '游戏中打开“设置 → 客户端设置”。\nAndroid 返回手势也可打开客户端设置。\n'
                  '页面与规则始终跟随服务器更新。首次登录仍需联网。',
                  style: TextStyle(
                      fontSize: 12, height: 1.7, color: Colors.white54)),
              const SizedBox(height: 18),
              const Text(
                  '非官方同人项目 · 仅供非商业学习交流\n'
                  '明日方舟相关素材版权归原权利人所有',
                  style: TextStyle(
                      fontSize: 11, height: 1.7, color: Colors.white38)),
            ]),
          ),
        ))),
      );
}

class GamePage extends StatefulWidget {
  const GamePage({super.key, required this.server, this.pack});
  final Uri server;
  final ResourcePack? pack;
  @override
  State<GamePage> createState() => _GamePageState();
}

class _GamePageState extends State<GamePage> {
  InAppWebViewController? _web;
  double _progress = 0;
  String? _loadError;
  bool _closing = false;
  final _gate = _ReadGate(4);

  Future<WebResourceResponse?> _resource(WebResourceRequest request) async {
    if (request.method != 'GET' || request.isForMainFrame == true) return null;
    final pack = widget.pack;
    if (pack == null) return null;
    final entry = pack.resolve(request.url.uriValue, widget.server);
    if (entry == null) {
      pack.misses++;
      return null;
    }
    final headers = request.headers ?? <String, String>{};
    String? rangeHeader;
    for (final item in headers.entries) {
      if (item.key.toLowerCase() == 'range') rangeHeader = item.value;
      // The remote server owns validator semantics.
      if (item.key.toLowerCase() == 'if-range') return null;
    }
    final length = entry['bytes'] as int;
    final range =
        rangeHeader == null ? null : ByteRange.parse(rangeHeader, length);
    if (rangeHeader != null && range == null) return null;
    // Bound transient platform-channel allocations; unusual large files fall
    // back to the WebView's streaming HTTP implementation.
    if (length > 32 * 1024 * 1024) return null;
    try {
      return await _gate.run(() async {
        final bytes = await pack.read(entry);
        if (bytes.length != length) return null;
        final body = range == null
            ? bytes
            : Uint8List.sublistView(
                bytes,
                range.start,
                range.end + 1,
              );
        pack.hits++;
        pack.bytesSaved += body.length;
        final mime = _mime(entry['asset'] as String);
        return WebResourceResponse(
          data: body,
          contentType: mime,
          contentEncoding: mime.startsWith('text/') ? 'utf-8' : null,
          statusCode: range == null ? 200 : 206,
          reasonPhrase: range == null ? 'OK' : 'Partial Content',
          headers: {
            'Content-Type': mime,
            'Content-Length': '${body.length}',
            'Accept-Ranges': 'bytes',
            'Cache-Control': 'public, max-age=31536000, immutable',
            'X-SP-Resource': 'apk',
            if (range != null)
              'Content-Range': 'bytes ${range.start}-${range.end}/$length',
          },
        );
      });
    } catch (_) {
      return null; // A missing/corrupt pack must not make the game unplayable.
    }
  }

  Future<void> _menu() async {
    final action = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
              title: const Text('客户端菜单'),
              content: Text('${widget.server.origin}\n\n'
                  '本地资源命中：${widget.pack?.hits ?? 0} 次\n'
                  '已代替网络读取：${((widget.pack?.bytesSaved ?? 0) / 1048576).toStringAsFixed(1)} MB\n\n'
                  '此数值为本地响应字节，不等同于实际链路流量。\n'
                  '重新加载或退出可能中断当前对局。'),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(ctx, 'exit'),
                    child: const Text('返回启动器')),
                TextButton(
                    onPressed: () => Navigator.pop(ctx, 'reload'),
                    child: const Text('重新加载')),
                FilledButton(
                    onPressed: () => Navigator.pop(ctx),
                    child: const Text('继续游戏')),
              ],
            ));
    if (!mounted) return;
    if (action == 'exit') {
      setState(() => _closing = true);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).pop();
      });
    } else if (action == 'reload') {
      setState(() => _loadError = null);
      await _web?.reload();
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: _closing,
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop) _menu();
        },
        child: Scaffold(
            body: Stack(children: [
          Positioned.fill(
              child: InAppWebView(
            initialUrlRequest:
                URLRequest(url: WebUri(widget.server.toString())),
            initialSettings: InAppWebViewSettings(
              javaScriptEnabled: true,
              useShouldInterceptRequest: Platform.isAndroid,
              useShouldOverrideUrlLoading: true,
              mediaPlaybackRequiresUserGesture: false,
              allowsInlineMediaPlayback: true,
              supportZoom: false,
              transparentBackground: false,
              isInspectable: false,
              allowFileAccess: false,
              allowContentAccess: false,
              mixedContentMode: MixedContentMode.MIXED_CONTENT_NEVER_ALLOW,
              thirdPartyCookiesEnabled: false,
            ),
            onWebViewCreated: (controller) => _web = controller,
            shouldInterceptRequest: (_, request) => _resource(request),
            shouldOverrideUrlLoading: (_, action) async {
              final uri = action.request.url?.uriValue;
              return uri != null &&
                      uri.scheme == 'https' &&
                      uri.origin == widget.server.origin
                  ? NavigationActionPolicy.ALLOW
                  : NavigationActionPolicy.CANCEL;
            },
            onLoadStart: (_, url) {
              if (mounted) {
                setState(() {
                  _progress = 0;
                  _loadError = null;
                });
              }
            },
            onProgressChanged: (_, value) {
              if (mounted) setState(() => _progress = value / 100);
            },
            onReceivedError: (_, request, error) {
              if (mounted && request.isForMainFrame == true) {
                setState(() => _loadError = '无法打开服务器：${error.description}');
              }
            },
            onReceivedHttpError: (_, request, response) {
              if (mounted && request.isForMainFrame == true) {
                setState(
                    () => _loadError = '服务器返回 HTTP ${response.statusCode}');
              }
            },
          )),
          if (_progress < 1 && _loadError == null)
            Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: LinearProgressIndicator(value: _progress)),
          if (_loadError != null)
            Center(
                child: Card(
                    child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                const Icon(Icons.wifi_off_rounded, size: 36),
                const SizedBox(height: 12),
                Text(_loadError!),
                const SizedBox(height: 16),
                FilledButton(
                    onPressed: () => _web?.reload(), child: const Text('重试')),
              ]),
            ))),
          Positioned(
              top: 6,
              right: 8,
              child: SafeArea(
                child: Material(
                    color: Colors.black54,
                    borderRadius: BorderRadius.circular(22),
                    child: IconButton(
                        tooltip: '客户端菜单',
                        onPressed: _menu,
                        icon: const Icon(Icons.more_horiz, size: 20),
                        visualDensity: VisualDensity.compact)),
              )),
        ])),
      );
}

String _mime(String path) {
  final ext = path.split('.').last.toLowerCase();
  return const {
        'png': 'image/png',
        'jpg': 'image/jpeg',
        'jpeg': 'image/jpeg',
        'webp': 'image/webp',
        'svg': 'image/svg+xml',
        'gif': 'image/gif',
        'mp3': 'audio/mpeg',
        'ogg': 'audio/ogg',
        'oga': 'audio/ogg',
        'wav': 'audio/wav',
        'm4a': 'audio/mp4',
        'aac': 'audio/aac',
        'opus': 'audio/ogg',
        'atlas': 'text/plain',
        'css': 'text/css',
        'json': 'application/json',
        'woff': 'font/woff',
        'woff2': 'font/woff2',
        'ttf': 'font/ttf',
        'otf': 'font/otf',
      }[ext] ??
      'application/octet-stream';
}

class _ReadGate {
  _ReadGate(this.limit);
  final int limit;
  int _active = 0;
  final _waiters = <Completer<void>>[];

  Future<T> run<T>(Future<T> Function() action) async {
    if (_active >= limit) {
      final waiter = Completer<void>();
      _waiters.add(waiter);
      await waiter.future;
    } else {
      _active++;
    }
    try {
      return await action();
    } finally {
      if (_waiters.isNotEmpty) {
        _waiters.removeAt(0).complete();
      } else {
        _active--;
      }
    }
  }
}
