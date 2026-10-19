# 卫戍协议 Flutter 客户端

[下载 Android 预览版](https://github.com/ChlorophyTeio/stronghold-client/releases/tag/v0.2.1-preview) · [更新维护说明](updates/README.md)

Android 0.2.1 预览版，强制连接 `https://test-spa.ppparty.top:8000`（spa-test）。
源码、素材导出和交付 APK 均在此目录；临时构建缓存见下文。本次修复不需要修改游戏容器。

## 当前功能

- Flutter 启动器：显示测试服状态、进入游戏；不提供服务器选择或地址编辑。
- Android 游戏页使用独立原生 Activity / 系统 WebView，避开 Flutter 平台视图叠层；启动器仍为 Flutter。
- 游戏窗口在 Android manifest 和生命周期中指定 sensorLandscape，全屏运行；返回启动器恢复竖屏。
- 应用图标和启动器图标直接使用 `spa-test/logo.webp` 容器原图。
- 沿用原服务器来源、Cookie 登录和 WebSocket 联机。
- APK 内置测试服源码目录的美术、字体和音频。
- 仅当远端资源 URL 的内容哈希与打包时的文件／Spine 模型哈希一致时，从 APK 读取。
- 未匹配资源交给 WebView 联网，避免旧素材覆盖新版素材。
- 页面、脚本、数据、模拟引擎、账号 API 始终通过原服务器加载；沿用服务器现有缓存和版本保护。
- 客户端菜单显示本地命中次数和响应字节；支持重新加载和返回启动器。
- Android 素材直接通过原生 InputStream 流式读取，支持音频单区间 Range；多区间、If-Range 回退网络。
- 加载超过 25 秒、HTTP/TLS 错误或渲染进程退出时显示提示；游戏设置中的“客户端设置”支持查看与复制诊断、纯在线资源重载。
- Android 原生窗口独立固定测试服来源；不读取旧服务器偏好，也不接受 MethodChannel 或 Intent 地址覆盖。服务器设置插件已移除。
- 同时保留依赖 JNI 所需类型名，发布包检查包含全部 CPU 架构的 Dart AOT 库。

新增签名素材增量更新、续传、回退和 APK 更新入口；设置支持仅更新代理。0.2.1 起更新源固定为 [ChlorophyTeio/stronghold-client](https://github.com/ChlorophyTeio/stronghold-client)，通过 Release 提供更新清单和安装包。详见 [更新维护说明](updates/README.md)。
这不是完整离线游戏，页面、联机与账号仍需服务器。
命中统计为本次进程替代的响应字节，不代表实测服务器链路流量。
不缓存账号接口，不导入数据库、不改造鉴权、不忽略 TLS 错误。

## 安装 APK

根目录的 `stronghold-client-0.2.1-preview.apk` 为通用 APK（armv7、arm64、x86_64）。
校验和见同名 `.sha256` 文件，仅保留当前版本交付包。新版可覆盖安装，不需卸载旧版。
完整检查记录和已知验证范围见 [BUILD-REPORT.md](BUILD-REPORT.md)。
当前使用开发签名，后续 APK 更新必须保持与已安装应用的签名兼容；本地签名与更新私钥均须保留。
Android 设备需要较新的系统 WebView，现有游戏依赖 ES modules、import maps 和 WebGL。
建议 Android 10 及以上并更新 Android System WebView / Chrome；最低安装版本与实际游戏兼容性不同。

启动后点击“进入游戏”，使用现有测试服账号登录。
游戏中通过“设置 → 客户端设置”进入，也可使用 Android 返回手势，退出或重新加载可能中断对局。
素材包较大，请为安装和 APK 解包预留足够空间。

## 构建

本次 Linux 构建工具独立安装在 `.toolchain/`；不依赖现有容器：

```bash
# 从测试服源码导出资源（只读源目录，输出到本项目）
node tool/export_resources.mjs ../spa-test/src

# 检查、测试、构建，并复制 APK 到根目录
./tool/build-apk.sh
```

`tool/flutterw` 将 Java、Android SDK、Gradle 与 Dart 包缓存都指向本目录。
当前 `build` 是到 `/tmp/spa-flutter-build` 的临时缓存链接，减少共享文件系统上大量 chmod 的开销；
源码、素材、APK 和检查记录保存在本目录，临时缓存丢失时可重新构建。
首次搭建环境须安装 Flutter SDK、JDK 17 和 Android SDK 到对应 `.toolchain/` 子目录。
依赖版本记录在 `pubspec.lock`，Android SDK 和 Flutter 版本见 `logs/`。
`assets/game/` 不提交版本控制，不包含服务器代码、环境变量或凭据。
源代码更新后，重新运行资源导出脚本并重新构建 APK。服务器没有对应内容哈希时使用在线资源。

当前固定 Flutter 3.47.7、JDK 17、Gradle 8.14.3、AGP 8.13.2 和 Kotlin 2.4.0。
Gradle 下载校验和写入 wrapper 配置。WebView 插件 6.1.5 的 Android 实现使用旧 ProGuard 配置，
与 AGP 9 不兼容，因此这里显式使用 AGP 8，不修改第三方包缓存来绕过问题。

## Windows 与后续计划

已生成 Windows 平台工程。Windows 包必须在 Windows 开发机上构建，
WebView2 初始化、资源请求映射和实机测试仍需完成，当前交付不宣称 Windows 已可用。

后续优先：真机更新下载、代理与整局回归 → 正式发布附件 → Windows 本地资源映射与安装包。

Android 原生规则测试在 `android/app/src/test/`，发布版运行测试在 `android/app/src/androidTest/`。
运行测试使用独立 Android 16 模拟器，仅以 GET 读取局域网 spa-test 未登录页面；
模拟器无公网 IPv6 路由，因此测试 APK 内有到 LAN 后端的读取适配。该适配不进入交付 APK，
也不更改正式客户端的 TLS 校验或服务器配置。

## 素材与源码

非官方同人项目，仅供非商业学习交流。《明日方舟》相关素材版权归原权利人所有，
不因随 APK 打包而获得新的授权；遵守上游 `NOTICE.md`。
美术资源不进入 Git。第三方 Flutter 包授权可在应用依赖和包源码中查阅。

Android 游戏视口按 DisplayCutout、RoundedCorner 和系统手势区域避让，随横屏方向更新。客户端设置入口仅在 APK 内通过页面 UI 钩子加入，不修改测试服页面，也不暴露 JavaScript 原生接口。

屏幕边距：进入“设置 → 客户端设置 → 屏幕边距”，可独立调整四边额外留白（0–64），拖动实时预览、自动保存；“恢复默认”将额外边距归零并保留系统自动避让。四边数值相对当前横屏方向应用。

如安装提示 v2 内容摘要不匹配，可使用根目录当前版本的 `stronghold-client-0.2.1-verified-*.zip`；先完整解压，再安装其中的 APK，校验值见包内 `SHA256SUMS.txt`。服务器副本已验证，手机端应比较文件哈希以确认传输完整性。发布脚本对交付副本验签后原子发布。


## 共享素材库与更新准备

公开美术、音频和字体现位于 `../asset-library/versions/<快照哈希>/`，Flutter 的 `assets/game/assets` 与 `fonts` 为相对目录链接，不再保存独立素材副本。`resource-library.lock.json` 固定版本。下载工程源码后，需要另外取得该共享库的固定快照；不能仅复制本工程而省略外部素材目录。

`tool/export_resources.mjs` 只校验共享快照并生成客户端资源索引和链接，不复制或删除共享素材。`tool/build-apk.sh` 会在构建前执行导出。0.2.0 新增更新设置；共享素材快照没有变化。素材审计见 [素材库说明](../asset-library/README.md)，更新实施状态见 [UPDATE-PLAN.md](UPDATE-PLAN.md)，发布命令与限制见 [更新维护说明](updates/README.md)。
