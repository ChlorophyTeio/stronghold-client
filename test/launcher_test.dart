import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stronghold_client/main.dart';
import 'package:stronghold_client/resource_pack.dart';

void main() {
  testWidgets('launcher exposes only production environment and no server controls',
      (tester) async {
    await tester.pumpWidget(
        ClientApp(pack: ResourcePack({}, totalBytes: 10 * 1048576)));
    expect(find.text('卫戍协议：盟约'), findsOneWidget);
    expect(find.text('当前连接：正式服'), findsOneWidget);
    expect(find.textContaining('10 MB'), findsOneWidget);
    expect(find.text('进入游戏'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(find.text('恢复正式服地址'), findsNothing);
    expect(defaultServer, 'https://spa.ppparty.top:8000');
  });

  testWidgets('resource failure still permits entering the fixed production server',
      (tester) async {
    await tester.pumpWidget(const ClientApp(packError: '本地资源包读取失败，游戏将使用在线资源'));
    expect(find.text('当前连接：正式服'), findsOneWidget);
    expect(find.text('本地资源包读取失败，游戏将使用在线资源'), findsOneWidget);
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNotNull);
  });
}
