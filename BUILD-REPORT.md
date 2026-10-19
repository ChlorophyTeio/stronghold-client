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
- OpenSSL 验证 Release 清单签名通过；清单中的 APK URL、版本号及 SHA-256 与交付包一致。

本机记录位于 `logs/0.2.1-*`，包括构建、签名、分包与运行验收。公开上传及真实 GitHub 下载验收结果在发布完成后补充。

## 范围

未修改、重建或重启任何现有容器；本次修改均限于 Flutter 工程与 GitHub 客户端仓库。共享素材、服务器配置、账号、数据库、工具链、签名私钥、GitHub 凭据、APK 和日志均未提交到源码仓库。构建脚本执行权限已写入 Git 索引。

正式手机安装、玩家实际代理、系统升级安装及整局联机仍需验收。本次不宣称 Windows 客户端或 Windows 更新器可用。

更新与发布维护步骤见 [updates/README.md](updates/README.md)。
