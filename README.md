# 卫戍协议 Flutter 客户端

[下载 Android 轻量版 0.3.1](https://github.com/ChlorophyTeio/stronghold-client/releases/tag/v0.3.1-preview) · [更新维护说明](updates/README.md) · [构建验收](BUILD-REPORT.md)

固定连接正式服 `https://spa.ppparty.top:8000/`，沿用现有游戏登录界面、账号 Cookie 和 WSS 联机。玩家不能更改游戏服务器。游戏页面和规则跟随服务器，客户端版本独立。

## 轻量安装与素材下载

APK 约 44 MiB，只包含程序、图标和空的素材索引，不包含游戏美术、音频或字体。安装后可直接在线打开原登录页，也可在启动页选择「下载游戏素材」，检查签名清单后确认下载。完整素材分包约 1.36 GiB，实际仅下载本机缺少的分包；下载前显示大小并要求确认，支持取消和续传。素材就绪后重新进入游戏生效。

仅更新代理支持直连、HTTP、SOCKS5 和 GitHub 加速前缀，不改变游戏网络。素材按 SHA-256 校验并保存在应用私有目录，游戏只使用 URL 内容哈希匹配的本地文件；缺失或失配交由游戏 HTTPS 在线加载。32 份 OBJ 模型保持在线加载，以兼容旧客户端的素材白名单。不缓存或替换页面、JavaScript、模拟引擎和账号接口。

从旧版覆盖升级无需卸载，可保留登录会话、手动边距、代理和已下载素材。新版完整资源清单也包含原来 APK 内置的素材，补齐轻量安装所需内容。请保留 GitHub 历史资源附件，最新清单会引用旧分包，避免重复上传。

## 游戏窗口

Android 使用独立原生 Activity / 系统 WebView，强制横屏和全屏，默认不避让挖孔、圆角或手势区。四边手动边距在「设置 → 客户端设置 → 屏幕边距」调整，恢复默认为零。Android 返回手势也可打开客户端设置；没有悬浮三个点按钮。图标沿用容器图标。

加载错误提供重载、诊断和纯在线资源选项。下载素材不会改变正在进行的对局；重新进入游戏时才选用新清单。APK 更新经哈希、包名、版本和签名检查后调用系统安装器，仍需用户确认。当前沿用原安装签名及更新签名密钥，私钥不上传 GitHub。

## 本地构建

```bash
./tool/build-apk.sh
```

轻量 APK 构建无需游戏素材库；脚本生成空索引，运行 Flutter 检查和测试，构建三个 ABI 的通用 APK，验签后发布到本目录。工具链与缓存位于忽略的 `.toolchain/`、`.gradle/`、`.pub-cache/`，临时构建输出在 `/tmp/spa-flutter-build`。固定 Flutter 3.47.7、JDK 17、Gradle 8.14.3、AGP 8.13.2、Kotlin 2.4.0；不要以关闭 TLS 验证解决下载问题。

素材发布另行只读导出测试服公开资源：

```bash
node tool/export_resources.mjs ../spa-test/src
node tool/verify_resources.mjs
```

`resource-library.lock.json` 固定共享素材快照；`assets/game/assets` 和 `fonts` 仅为库链接，不进入 APK 或 Git。完整资源目录索引写入忽略的 `updates/resource-index.json`，APK 内的 `assets/game/index.json` 始终为空。不得原地修改共享素材快照、游戏容器或正式服文件。资源发布命令及复用旧包见更新维护说明。

Windows 平台工程保留，原生资源下载和 WebView2 实机验收尚未完成，当前交付仅面向 Android。建议 Android 10 及以上，并使用较新的系统 WebView。

非官方同人项目，仅供非商业学习交流。《明日方舟》相关素材版权归原权利人所有，不因下载或打包获得新授权；见 `NOTICE.upstream.md`。游戏源码和素材不随 Git 仓库提交，服务端账号与数据库不进入客户端。
