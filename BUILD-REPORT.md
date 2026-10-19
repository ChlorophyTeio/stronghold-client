# Android 0.2.3：固定连接正式服

- 版本：0.2.3 / versionCode 9；包名 `top.ppparty.stronghold_client`，更新清单 sequence 3。
- 来源：`https://spa.ppparty.top:8000/`。Flutter 启动器及 Android 原生窗口同时固定该来源，不提供服务器选择，也不接受旧偏好或 Intent 地址覆盖。
- 启动页、游戏加载/错误提示、客户端设置及更新页均显示正式服。
- 沿用既有 APK 安装签名、ECDSA 更新密钥、公钥及 `ChlorophyTeio/stronghold-client` 更新源，支持从旧版覆盖升级。
- 延续全屏优先：不自动避让挖孔、圆角或手势区；仅用户手动边距生效，恢复默认归零。保留边距及仅更新代理设置。
- 切换 HTTPS 来源后需在正式服重新登录；不搬运测试服 cookie 或账号凭据。游戏流量继续独立于更新代理。

## 素材与服务器范围

只读检查两服运行容器及 Compose：均挂载 `97a5a4d85f07b6641943637ff24045afc4d840adb2abd56f9eaba9df40cad947` 素材快照，assets/fonts 均只读。客户端锁文件与此一致，导出脚本仍从测试服源码只读生成元数据，资源按完整来源与内容哈希匹配。没有重复素材分包。

正式服和测试服 LAN 首页、healthz 均 HTTP 200；本环境访问两个公网 HTTPS 入口均遭遇 TLS EOF，因此模拟器的只读页面回归使用独立测试 APK 中已有的 LAN 传输适配器，生产 APK 未加入该适配器或 TLS 绕过。

没有修改或重新部署游戏容器、游戏源码、数据库或共享素材快照。运行前后容器镜像、启动时间、健康状态及只读挂载一致，记录：`logs/0.2.3-containers-before.json`、`logs/0.2.3-containers-after.json`。

## 验收

- APK `stronghold-client-0.2.3-preview.apk`：632,513,676 字节，SHA-256 `5e6a4806c793f0ea0d384b1180be945d5adeda3190a4dc9388702291a1618fe4`。
- 素材 25,415 文件全量校验、Python 更新打包测试、Flutter analyze、2 项启动页测试及 16 项原生 JUnit 通过。Flutter 资源测试首轮宿主段错误，单独复测 6 项全部通过，记录：`logs/0.2.3-resource-test-retry.log`。
- APK 库存/资源哈希、三个 ABI 的 AOT、容器图标、签名公钥、无私钥/测试夹具、APK v2 签名及 16 KiB zipalign 通过。
- 正式服 LAN 页面通过三次加载、正反横屏、模拟挖孔及零自动边距、手动边距调整/保存/恢复默认、客户端设置入口、原生本地音频 Range 206 / 8 字节；旧服务器偏好和 Intent 地址覆盖无效。记录：`logs/0.2.3-game-runtime.log`。首轮 UI 返回操作超时，未修改交付包复测通过，失败日志同样保留。
- 更新清单沿用原私钥签名并独立验签通过，sequence 3 / APK code 9，资源分包为零。

独立测试 APK 只执行只读 GET，不创建账号或对局。

## 公开发布与旧版升级

- [v0.2.3-preview](https://github.com/ChlorophyTeio/stronghold-client/releases/tag/v0.2.3-preview) 已公开，四个附件的 GitHub 大小及内容 SHA-256 与本地一致。签名清单经公开 HTTPS 再次验证，sequence 3 / APK code 9；记录：`logs/0.2.3-public-release-verification.json`。
- 实际 0.2.2 / code 8 使用原更新器通过“仅更新 HTTP 代理”检查公开 Release，完整下载 632,513,676 字节 APK，原生及测试驱动独立校验 SHA-256，均为 `5e6a4806c793f0ea0d384b1180be945d5adeda3190a4dc9388702291a1618fe4`。记录：`logs/0.2.3-old-client-download.log`。代理配置仅在独立测试驱动和忽略的本地日志中，未硬编码进入 APK。
- 旧版“安装已验证的 APK”交给系统安装器覆盖升级，实际包版本从 code 8 变为 code 9；升级前保存的左右边距 13 / 7 保留。记录：`logs/0.2.3-system-install.log`、`logs/0.2.3-after-upgrade-package.txt`、`logs/0.2.3-upgrade-verification.log`。安装时旧 instrumentation 随旧进程被系统终止，实际成功由主机独立检查和新进程测试确认。
- 覆盖升级后完整回归通过，确认 `fixedProductionServer=true`、`fullBleed=true`、正反横屏、三次渲染、手动边距、客户端设置入口及音频 Range。记录：`logs/0.2.3-upgraded-game-runtime.log`。自动化全局返回键多次超时后，测试驱动改用玩家实际的网页设置入口重新打开菜单并通过；该修改仅在独立测试 APK 中，交付 APK 未重建或替换。
- 上传连接在服务器已保存全部附件、公开验签及旧版完整下载验证之后仍未结束，已关闭该上传进程；以服务端附件状态、哈希及实际匿名客户端下载结果为准。
- 本地 APK 及已验证 ZIP 放在项目根目录；旧版 0.2.2 本地交付副本已清理，GitHub 历史版本和素材基线保留。

小米真机仍需用户安装后确认；不能用模拟器结果替代真机验收。
