# Android 更新发布与维护

0.2.1 起更新源为公开仓库 [ChlorophyTeio/stronghold-client](https://github.com/ChlorophyTeio/stronghold-client)。当前 0.3.1 游戏连接固定为正式服；玩家不能更改游戏服务器。
入口为启动页“更新设置”，以及游戏“设置 → 客户端设置 → 更新设置”。
Windows 下载器及客户端代码热替换不在本次实现范围。

## 更新方式

- 公开 GitHub 仓库仅存客户端源码、构建工具与元数据。素材包、APK 和清单放 Release 附件；不提交原始素材、服务器配置、账户、数据库或任何私钥。
- 客户端通过 GitHub Releases API 查找最近 20 个已发布 Release 中首个 `update-preview.json` 附件，因此支持预发布版本。发布时使用独立版本 tag，上传完整后再公开；不要先公开空清单或删除玩家仍需使用的旧附件。
- 清单使用 ECDSA P-256 / SHA-256 签名，外层为 Base64 payload/signature；签名验证原始 payload 字节。内嵌公钥固定，`sequence` 必须单调增加。资源回退通过单独按钮执行，不接受网络旧清单覆盖。
- 0.3.1 起 APK 不内置游戏素材，默认发布完整资源清单。`0.2.0-index.json.gz` 仅是旧版历史基线，不能再单独依赖它发布差异，否则轻量版会缺素材。用 `--reuse-manifest` 验证并复用旧清单的分包 URL，只为仍未覆盖的内容生成新分包。所有当前资源必须被分包覆盖；旧客户端已内置、已下载的内容按哈希复用。同一分包中的其他资源可能一并下载。目标每包不超过 16 MiB，超大单文件独立分包。
- 下载只写应用私有 `files/updates/staging`。支持取消和 HTTP Range 续传；服务器不支持 Range 时重新完整下载。逐包 SHA-256、逐文件 SHA-256、长度、ZIP 成员名单与空间校验通过后，写入按哈希寻址的 blobs，再通过 AtomicFile 切换活动清单。
- 每个游戏窗口仅在创建时读取活动清单，期间不会换版本。读取顺序为“下载素材 → APK 素材（旧版）→ 正式服”，均要求服务器 URL 中的哈希匹配；失配或缺失仍走游戏 HTTPS。保留上一版清单及资源用于回退。保留的旧 blob 和未完成下载目前不自动回收，以免删除进行中对局的资源；后续加入引用与空间管理。
- 不缓存替换游戏 HTML、JavaScript、API、WebSocket 或登录数据；两份字体素材 CSS 例外，属于素材清单。界面规则仍跟随正式服。
- APK 先验签名清单中的 SHA-256，再校验包名、版本号、签名证书与当前应用一致；安装前再次检查文件。系统安装器最终校验 APK 签名并要求用户确认。不会静默安装，也不会自动退出对局。

## 代理

默认直连，关闭更新代理时显式使用 NO_PROXY；系统级 VPN 等路由仍由系统决定。

支持 HTTP 连接代理（可选 Basic 认证）、无认证 SOCKS5，以及 HTTPS GitHub URL 加速前缀。SOCKS5 暂不支持用户名密码认证。代理信息只保存在本应用私有偏好设置，不进入资源包或发布清单；应用禁止系统备份。无预置第三方加速地址。

更新器独立持有 OkHttpClient，CookieJar 默认不保存或发送 Cookie，不使用游戏令牌。仅使用 proxyAuthenticator 处理代理 CONNECT 认证，不设置源站 Authorization，不修改全局 ProxySelector / Authenticator / CookieHandler，不改 WebView 代理。HTTPS 重定向保持更新网络配置与 Range，禁止降级为 HTTP，最多 8 跳。加速模式拼接 `前缀/https://github.com/...`，只接收 GitHub 更新地址；代理服务必须支持对应 URL 格式及 GitHub API。

## 更新源配置

```bash
cd /mnt/user/appdata/arknights/flutter
python3 tool/configure-updates.py ChlorophyTeio/stronghold-client
./tool/build-apk.sh
```

仓库地址属于更新源，不是游戏服务器选择。玩家不能切换游戏服务器。公开仓库可直接下载；不把 GitHub token 嵌入 APK。私有仓库下载不支持。

更新签名私钥已保存在 `.toolchain/update-signing/private.pem`（0600、已被 git 忽略）。应离线备份此私钥及现有 APK 签名密钥；丢失任一个都不能无缝延续对应更新信任。**不要重新生成或上传私钥**。Android 内嵌 `update-public.pem` 可以公开。未来换公钥需要通过受信任的 APK 升级。

## 生成 Release 附件

先按素材库规则创建新的不可变快照，在测试服验证，再更新 Flutter 锁文件并执行导出；禁止原地修改共享快照或正式服。素材没有变化时，也可以发布包含 APK 的更新清单。

```bash
node tool/export_resources.mjs
node tool/verify_resources.mjs
python3 tool/build-update.py \
  --output updates/releases/v0.3.1-preview \
  --base-url https://github.com/ChlorophyTeio/stronghold-client/releases/download/v0.3.1-preview \
  --full --exclude-obj \
  --reuse-manifest updates/releases/v0.3.1-resources/update-preview.json \
  --sequence 5 --version game-0.3.1-light-complete \
  --notes '本次修改说明' \
  --apk stronghold-client-0.3.1-preview.apk \
  --apk-version-code 10 \
  --apk-certificate 0a55727d7de19ccd2d3635716cab9bcc2d3948a5fd0389984eaad70439e872f1
```

仅更新素材时省略三个 APK 参数。当前签名证书为开发签名；维持覆盖升级必须沿用它。打包器不会上传，也不会复制 APK；Release 需要附上输出目录的 `update-preview.json`、全部 `resources-*.zip`，以及命令指定的原 APK。不要附上私钥、工具链或整个素材目录。首次发布可从 sequence 1 开始，此后递增。公开前核对发布 URL 与附件实际 tag/文件名，检查 APK 的 apksigner 结果。不能在已有清单发布后才修改 APK。

`--full` 可生成完整资源分包，通常不需要。资源清单必须覆盖轻量版所需的全部文件，不能仅生成相对旧 APK 的差异。更新 APK 的最低基线策略变化时，需要保留旧客户端能使用的累计包，或先要求升级 APK。

## 本地验证

```bash
python3 tool/test-updates.py
python3 tool/prepare-update-tests.py
```

前者验证增量包、组合哈希保留、OpenSSL 验签、篡改拒绝和源文件变化拒绝。后者用本机私钥生成仅用于 androidTest 的离线签名夹具（目录已忽略，不进正式 APK），供 NativeSmokeInstrumentation 的 `updatesOnly=true` 模式验证：无效签名、损坏包、取消、原子激活、活动对局版本保持、回退、反重放、代理不改变全局网络配置及已配置/未配置更新源时的界面。JUnit 网络测试使用仅测试客户端信任的本地 TLS 证书；正式下载器保持系统证书校验。

正式公开前还需在小米真机、实际 GitHub Release、玩家使用的代理和系统安装器上完成端到端验收。模拟器与本地 TLS 测试不能替代这些外部条件。

## 公开附件验收

发布后可匿名核对 GitHub 保存的附件 SHA-256、签名清单及完整 APK 下载内容：

```bash
python3 tool/verify-release.py --tag v0.2.3-preview \
  --local-apk stronghold-client-0.2.3-preview.apk --download-apk \
  --output logs/0.2.3-public-release-verification.json
```

此检查不使用 GitHub 凭据，不修改游戏配置或全局代理；下载只做流式哈希，不另存一份 APK。

## 游戏 0.3.1 素材更新（2026-10-22）

游戏正式服为 0.3.1，Flutter 壳独立版本仍为 0.2.3。Release `v0.3.1-resources` 使用 sequence 4，同步素材快照 `9a54dcd5111b49ce225611ec7a8634f8d7415c981b97c4ac5ace554222f24d01`。更新设置检查后下载并启用素材，重新进入游戏生效。32 份 OBJ 文件因旧客户端白名单限制不进入更新清单，继续从游戏 HTTPS 加载。无游戏代码热替换或 KCP。

素材增量仍相对 0.2.0 APK 基线累计生成。打包时使用完整导出索引中剔除 `.obj` 路径的兼容索引；不要改变旧客户端允许的文件类型。通过 `--apk-url` 可引用同一仓库既有 Release 的 APK，仍须传本地已验证 APK、版本码和证书，清单记录其原始哈希和长度，不重新上传同一文件。

## 轻量版 0.3.1（2026-10-22）

包名和安装签名保持，versionCode 10，清单 sequence 5。APK 无游戏素材，启动器保留进入原登录页的入口，并新增「下载游戏素材」。用户确认后再下载，可取消/续传；仅更新代理保持独立。新清单复用 v0.3.1-resources 的 256 个分包，并补齐原 APK 的素材，共 512 个分包。旧 Release 不可删除。升级导致已激活版本的文件缺失时，可以重新执行同版本下载修复，不提前返回成功。

元数据导出现在写入 updates/resource-index.json，APK 仅打包自动生成的空索引。`--exclude-obj` 将 32 份 OBJ 文件留给游戏在线加载。`--base-index` 只用于明确指定的旧版测试或历史包，当前轻量发布必须使用完整清单。
