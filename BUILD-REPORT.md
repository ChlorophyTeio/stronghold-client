# Android 0.2.2：全屏优先与 APK 更新验收

- 版本：0.2.2，versionCode 8；包名 `top.ppparty.stronghold_client`。固定 spa-test。
- APK：`stronghold-client-0.2.2-preview.apk`，632,513,672 字节。
- SHA-256：`25c3f925368518980c7b23687f25212c60b9464605faf58f210f4a6ada53a622`。
- 更新源：[ChlorophyTeio/stronghold-client](https://github.com/ChlorophyTeio/stronghold-client)。沿用 0.2.1 的安装签名和更新公钥，清单 sequence 2。

## 显示修正

游戏窗口优先覆盖整个屏幕，不再对挖孔、圆角、系统手势或系统栏自动增加边距。原生视口只使用玩家手动设置的四边数值；网页的四个安全区变量同样归零，在页面首次可见及加载完成时通过客户端钩子应用，不修改服务器页面。

滑条继续支持预览、保存及恢复默认。“恢复默认”将四边归零，恢复全屏。已有手动数值保留，升级不会偷偷覆盖玩家设置；希望无额外留白时可点恢复默认。

本次包含 Android 原生窗口改动，通过 APK 更新生效：0.2.1 中先检查更新，再下载客户端 APK，并选择安装已验证的 APK。素材快照及模型元数据未改变，无重复资源分包。

## 发布前验收

- 素材全量校验、Python 增量打包测试、Flutter analyze 通过；6 项资源规则和单独重跑的 2 项启动页测试通过。Linux Flutter 测试宿主仍出现过段错误，保留失败及重跑记录。
- 原生 JUnit 16 项检查及 Android 发布测试 APK 编译通过。
- 实际 minified release 安装在 Android 16 模拟器，启用模拟挖孔并验证该挖孔确实存在：根视图覆盖完整物理显示、位置为 (0,0)、四边默认 padding 均为 0；WebView 同样铺满；网页 --sa-t/r/b/l 全部为 0px。
- 同轮通过：三次页面渲染、固定测试服、正反横屏全屏几何、四边滑条实时调整/保存/回显/恢复零边距、设置入口、音频 Range 206 / 8 字节。
- APK 库存、三个 ABI 的 AOT、容器图标、源配置/公钥、无测试夹具或私钥、APK v2 签名与 16 KiB zipalign 通过。
- sequence 2 的更新清单已用原私钥签名并经公钥验证；APK 版本号、内容哈希与固定 Release 附件 URL 一致。

原生显示验收记录：`logs/0.2.2-game-runtime.log`；截图：`logs/0.2.2-runtime/native-title-0.png`、`native-margin-default.png`、`native-reverse.png`。

公开发布与旧客户端应用内升级验收完成后追加记录。升级测试从 code 7 出发，使用旧版的实际下载器和系统安装器；测试代码仅在独立 Android 测试 APK 内，不进入交付包。

## 范围

本次不修改或部署任何现有游戏容器，不改共享素材快照；修改仅限 Flutter 客户端和其 GitHub 仓库。更新凭据、签名私钥、原始素材、APK、工具链与日志不进入源码 Git。

尚未连接小米真机；最终手机显示及系统安装体验仍待玩家验收。源码与更新维护说明见 [README.md](README.md) 和 [updates/README.md](updates/README.md)。
