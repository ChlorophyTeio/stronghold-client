# Android 0.2.1：接入 GitHub 更新源

- 仓库：[ChlorophyTeio/stronghold-client](https://github.com/ChlorophyTeio/stronghold-client)。公开源码仅约 2 MB，素材与安装包通过 Release 分发。
- 版本：0.2.1，versionCode 7；包名 `top.ppparty.stronghold_client`。游戏仍固定 spa-test。
- APK：`stronghold-client-0.2.1-preview.apk`，632,513,516 字节。
- SHA-256：`d7b341c27e8bbb1c8daa2d9fccb60bc84f4289503db4094f7f7551df3379fea2`。
- 更新源已固定为本仓库，沿用 0.2.0 APK 签名与更新验签公钥。0.2.0 没有内置仓库地址，需先手动安装 0.2.1 一次，随后可在“更新设置”中检查 Release。

## 本版变化与素材

本版接入真实仓库地址并更新版本号，保留签名素材增量更新、续传/取消/回退、独立更新代理、APK 校验与系统安装入口、原生横屏和四边手动边距。同步更新了已配置仓库时的界面测试，并加入实际 GitHub 签名清单检查与资源启用的运行测试入口。

素材快照仍为 `97a5a4d85f07b6641943637ff24045afc4d840adb2abd56f9eaba9df40cad947`，未修改共享库。共 25,415 个物理文件，858,620,904 字节；索引含 27,124 个路径（包括别名）。相对冻结的 0.2.0 内置基线无差异，首个 Release 不生成重复资源分包。

更新清单为 `update-preview.json`，sequence 1，resourceVersion `baseline-97a5a4d8`；由本机保留的 ECDSA P-256 私钥签名，经内嵌公钥验证。APK 的内容 SHA-256、版本号与证书摘要写入签名 payload，URL 固定指向 `v0.2.1-preview` Release 附件。

## 验证

- Flutter analyze 无问题；6 项资源测试与 2 项启动页测试通过。整体测试曾因本机 Linux flutter_tester 段错误中断；启动页单独重跑通过，未放宽测试断言。
- 原生 JUnit 16 项检查通过；实际 release 的 Android 测试 APK 已编译。此前 0.2.0 已验收的游戏横屏、挖孔安全区、滑条保存/恢复和音频 Range 逻辑未变更。
- 资源导出校验、Python 增量打包与篡改拒绝测试通过。
- 最终 APK 交付副本通过三个 ABI 的 AOT 库、容器图标、更新源/公钥、无私钥/测试夹具、APK v2 签名及 16 KiB zipalign 检查，随后原子发布。
- 实际 0.2.1 release APK 在 Android 16 模拟器覆盖安装成功；完整基线验签、损坏拒绝、取消、原子激活、回退、旧清单拒绝、下载资源 Range 读取、对局资源版本保持、代理隔离及已配置更新源界面均通过。
- OpenSSL 验证 Release 清单签名通过；清单中的 APK URL、版本号及 SHA-256 与交付包一致。

本机记录位于 `logs/0.2.1-*`，包括构建、签名、分包与运行验收。公开 Release 及真实 GitHub 下载验收均已完成，见下文。

## 范围

未修改、重建或重启任何现有容器；本次修改均限于 Flutter 工程与 GitHub 客户端仓库。共享素材、服务器配置、账号、数据库、工具链、签名私钥、GitHub 凭据、APK 和日志均未提交到源码仓库。构建脚本执行权限已写入 Git 索引。

正式手机安装、玩家实际代理、系统升级安装及整局联机仍需验收。本次不宣称 Windows 客户端或 Windows 更新器可用。

更新与发布维护步骤见 [updates/README.md](updates/README.md)。

## GitHub 发布与在线验收

[首个公开 Release：v0.2.1-preview](https://github.com/ChlorophyTeio/stronghold-client/releases/tag/v0.2.1-preview) 已发布为预览版，源码 tag 指向 `8e543044ea3cfd7488cf563c3979abfa3e4986e3`。四个附件为 APK、APK 校验和、签名更新清单及清单校验和。GitHub 返回的全部附件大小与 SHA-256 均与已验证本地文件一致。

通过公开地址匿名完整下载 APK 并流式计算 SHA-256，通过：632,513,516 字节，`d7b341c27e8bbb1c8daa2d9fccb60bc84f4289503db4094f7f7551df3379fea2`。OpenSSL 对公开签名清单验签通过。可复用 `tool/verify-release.py` 执行同样检查，无需 GitHub 凭据。

Android 16 模拟器使用实际发布 APK、默认直连配置和正式更新界面，直接访问真实 GitHub API 与附件重定向，成功验证清单签名并启用 sequence 1 的 `baseline-97a5a4d8` 素材版本。本检查未替换更新下载器、未关闭 TLS 校验。记录：`logs/0.2.1-github-runtime.log`；截图：`logs/0.2.1-runtime/update-online.png`。同一 APK 的三次游戏渲染、正反横屏、挖孔避让、滑条保存/恢复及音频 Range 回归通过。

网络异常记录：上传命令末尾返回 422 同名附件存在；随后确认 GitHub 保存的 APK 已完整上传且 SHA-256 正确，未重复覆盖文件。第一次匿名完整下载未通过校验，重试后全字节读取与哈希核对通过；保留两次日志以便追踪传输问题。GitHub 网页授权第一次因连接中断未保存凭据，重新授权后成功。

本机额外提供 `stronghold-client-0.2.1-verified-d7b341c2.zip` 及校验和，供文件传输时使用；GitHub 仅上传一份 APK，避免重复分发同一安装包。旧版 0.2.0 APK/ZIP 及校验和已清理，内置素材基线元数据、素材快照、密钥及历史验收记录均保留。
