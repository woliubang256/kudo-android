# Agent Note: 固定开发签名与按 Tag 发布正式包

Status: implemented

## Problem

Debug 依赖开发机器的默认证书，Release 没有签名配置，仓库没有 GitHub Actions。不同机器的安装包无法保持签名一致，手动打包也不能保证版本名、签名与发布 Tag 对应。

## Decision

Debug 使用独立包名 `com.kudo.android.debug`、桌面名称“酷DO 开发版”，Release 保持 `com.kudo.android`。两者的应用目录、Cookie、FileProvider authority 和测试包身份分别隔离；固定开发签名继续沿用。Debug 的网页链接过滤器使用保留域名 `debug.kudo.invalid`，不参与 linux.do 链接关联。真机开发安装显式传入不低于当前安装的版本，核对目标包名与签名后使用 `adb install -r`，不卸载或清除任一应用数据。此项属于原构建身份决定的补充，不归档既有签名与发布规则。

独立包名允许与正式版共存，代价是开发版首次使用需要独立登录。继续同包名能直接复用会话，但无法满足用户保留正式版并行验收的要求；临时修改 applicationId 只对一次构建生效，容易在下一次安装误覆盖，因此使用固定 debug 构建类型。

2026-10-23 使用显式 `0.3.1-dev / 3002` 构建 `processDebugMainManifest` 通过；安装前真机正式版为 `0.3.1 / 3002`，尚无 `.debug` 应用。APK 安装与共存检查随本地优选单元集中验收。

开发版的首页不触发正式 Release 自动检查，关于页显示开发版版本，不提供无法覆盖本包的正式更新操作。正式版继续原有更新流程；界面测试显式注入更新源时仍可检查更新组件。仅依赖安装器拒绝不同包名能够避免误覆盖，但会先下载无用 APK 并显示误导错误，因此在入口隔离。

`docs/BUILDING.md` 的开发启动命令显式区分应用 ID 与类名：`com.kudo.android.debug/com.kudo.android.MainActivity`；手动 instrumentation 使用 `com.kudo.android.debug.test`。构建/安装示例携带版本参数，Rust 宿主测试产物放入已忽略的 `app/build`。旧同包名的测试 APK 可以保留，但本轮不能向正式包运行旧 instrumentation。

当前本地正式打包版本为 `0.2.2` / `2003`；最新已公开正式 Release 仍为 `v0.2.0`，`v0.2.1` 已有本地和远端 Tag，但没有已公开 Release。本次只打包，不创建 `v0.2.2` Tag 或发布远端附件。Release APK 只打包 `arm64-v8a`，发布脚本将验证 APK 的 `native-code` 清单只包含该 ABI；公开产物统一命名为 `kodo_<version>_release_arm64-v8a.apk` 及对应的 `.sha256` 文件。

0.2.2 本地验收：使用 `-PversionName=0.2.2 -PversionCode=2003` 完成 Release R8 构建与 Lint，默认 Gradle 版本保持不变。产物为 `artifacts/release/0.2.2/kodo_0.2.2_release_arm64-v8a.apk`，9138426 字节，SHA256 为 `30cf6e9762d89192647e1b9db5489c9a8b75669323897eb09cd90a9874671d88`。正式证书与既有版本一致，包名、版本、单 arm64-v8a、非 debuggable、16KB ZIP 和三份 native ELF LOAD 对齐全部通过，详情为同目录 `verification.json` / `verification.txt`，源码提交为 `188e09e48f212abfd6461e65a261ad7b06d45687`。构建日志为 `artifacts/release-0.2.2-build.txt`；Release Lint 为 0 errors / 44 warnings / 1 hint，96篇笔记门禁通过。此前楼层与置顶、签名改动的19项相关 JVM 和29项不同真机用例已通过，本轮未重复无关 UI 测试，未把构建和验签视为正式包真机运行验收。手机保留 `0.2.2-dev / 2005` 开发版和数据，未安装正式包。通过认证 API 核对实际已公开基线为 `v0.2.0`，重新提取并归并26条 App 提交为16条更新说明；`release-notes/v0.2.2.md` 通过 HEAD 来源校验，原始候选和最终正文保存在产物目录。

0.2.1 本地验收：通过 `-PversionName=0.2.1 -PversionCode=2002` 构建，默认版本保持不变；产物为 `artifacts/release/0.2.1/kodo_0.2.1_release_arm64-v8a.apk`，9138430 字节，SHA256 为 `427165c7c3e6c2249262d844351da3071fb0788d4f4bf395f107b7b27adb8b90`。正式签名与既有版本一致，版本、单 arm64-v8a、非 debuggable、16KB ZIP 与 native ELF LOAD 对齐通过（同目录 `verification.json` / `verification.txt`）。发布前将访客和已登录个人中心入口统一为“设置”，6项相关真机界面测试通过（`artifacts/release-0.2.1-settings-final-device.txt`）；此前短帖计数的16项相关回归通过。开发包使用同签名 `adb install -r` 保留数据，未安装正式包。Release R8 / Lint 与90篇笔记门禁通过；云端附件由 Tag 工作流另行构建和验收，本机结果不替代云端结果。

0.2.0 本地验收：使用 Gradle 参数 `-PversionName=0.2.0 -PversionCode=2001` 构建，默认版本不变。最终 APK 为 `artifacts/release/0.2.0/kodo_0.2.0_release_arm64-v8a.apk`，8.70 MiB，SHA256 为 `c36a8c4d2e458a90619729ce719f2921b64374242f5df243286175f1672c5bdd`；与 0.1.9 正式证书一致，版本、单 arm64-v8a、非 debuggable、16KB ZIP 对齐和三份 native ELF LOAD 对齐通过（同目录 `local-verification.txt`）。46 项相关 JVM 测试、最终 Release R8 / Lint、40 项 RMX2202 / Android 13 设备回归及两项图片帧探针通过。日志分别为 `artifacts/image-spoiler-final-build.txt`、`release-0.2.0-final-build.txt`、`release-0.2.0-final-device.txt`、`gallery-frame-probe-final-device.txt`；Lint 为 0 errors / 41 warnings / 1 hint。手机使用同签名开发包 `install -r`，不安装正式包、不卸载或清数据。用户随后要求修复云端构建并删除重推同名 Tag；v0.2.0 已完成云端发布及实际附件验收，App 更新日志以此前已公开的 v0.1.5 为基线。

`release_version.py` 集中定义发布 ABI 并生成小写文件名，`prepare_release.py` 在复制 APK 前解析 `aapt dump badging`，缺少 native-code、包含多个 ABI 或出现其他 ABI 都会拒绝发布。

生成器创建独立的 Debug、Release RSA 4096 / PKCS12 密钥库，有效期 10000 天，分别导出公开证书及 SHA256 指纹。私钥、密码与生成结果保存在忽略 Git 的 signing/ 和 signing.properties；生成器遇到已有文件必须拒绝覆盖。固定两套签名使本机与 CI 使用相同更新身份，原 Android 默认 Debug 密钥不被修改。

Gradle 从本地 signing.properties 读取各变体配置，DEBUG_* / RELEASE_* 环境变量优先，signingPropertiesFile 可切换本地配置文件。配置自定义 Debug 证书时使用固定开发签名；没有私有配置的普通源码克隆仍可使用 Android 默认 Debug 签名。Release 构建缺少签名时明确失败，不能静默生成未签名包或回退 Debug 证书。密码经环境变量传给 keytool，生成与验签脚本不打印密码或私钥。

Tag 发布只接受 vX.Y.Z，三位组件均为单个 0 到 9 的数字；版本名去掉 v，versionCode 使用 major×1000000 + minor×1000 + patch + 1。补丁位从 0.1.0 递增到 0.1.9 后进位为 0.2.0，大功能提升 minor 或 major 位。拒绝前导零、预发布后缀与其他 Tag 格式，避免不同 Tag 映射到同一版本号。普通本地构建保留 0.1.0 / 1 默认值，可用 versionName、versionCode Gradle 属性覆盖。Tag 通过环境变量进入带引号的 shell 参数，不能直接插入命令源码。

GitHub Actions 在 v 开头的 Tag 推送时校验 Tag、执行脚本测试、笔记校验、JVM 测试及 Lint，然后从 GitHub Secrets 恢复 Release 密钥库，构建签名 APK。工具链为 Ubuntu 24.04、JDK 21、Node 22、Android SDK 36 和 Build Tools 35.0.0；外部 Action 固定为已向官方仓库核实的提交 SHA。Windows 的 gradlew.bat 与 Linux 的 bash ./gradlew 均可使用仓库 Wrapper。

JVM检查失败时单独上传JUnit XML和HTML报告，保留14天，名称包含Tag与run_attempt；没有报告时忽略。该步骤不绕过失败门禁，不上传签名配置或会话数据。仅保留job日志最简单，但v0.1.8的日志没有UncaughtExceptionsBeforeTest的被抑制异常及具体等待阶段，不能支持调度根因分析，因此同时保留完整测试报告。

构建作业只读仓库，关闭共享 Gradle 缓存和 configuration cache；四个 Secrets 为 RELEASE_KEYSTORE_BASE64、RELEASE_STORE_PASSWORD、RELEASE_KEY_ALIAS、RELEASE_KEY_PASSWORD。密钥放 runner.temp，验证完成或失败后均尝试移除。prepare_release.py 使用 apksigner 验证 APK 完整性，比较唯一签名证书与正式密钥库导出的证书，使用 aapt 验证包名、Tag 版本及非 debuggable 标记，全部通过后才复制 APK 和生成对应 SHA256 文件。

发布作业下载APK、校验文件和[提交日志](2026-10-11-commit-release-notes.md)生成的正文并复查 SHA256，使用目标仓库的 GITHUB_TOKEN 写 Release，不需要额外长期发布令牌。它先创建草稿、上传完整文件后再公开；同 Tag 遗留草稿可重试，已公开版本拒绝覆盖。检查或构建失败不会进入发布作业，上传失败不会公开不完整草稿。Actions 中转产物保留 14 天，公开 Release 附件不受此限制。

本机 Git 认证使用 Git Credential Manager 的 Windows Credential Manager 存储（wincredman），origin 仅保存不含凭据的 HTTPS 地址。读取远程或认证信息时先在进程内脱敏，不能直接输出带凭据的 URL 或 credential fill 的原始结果。GitHub Secrets 由 GitHub CLI 加密提交，密钥及密码通过标准输入传递，GitHub 访问令牌只进入子进程环境。

## Action runtime

v0.1.5 的 build、publish 虽然成功，使用的七个 Action 的 action.yml 都声明 node20，因此 GitHub 强制使用 Node 24 时产生弃用警告，setup-java v4 另有版本弃用提示。Action 自身的运行时由上游 action.yml 决定，与 setup-node 安装供笔记脚本使用的 Node 22 分开管理。

工作流使用截至 2026-10-11 已从各官方仓库核实的稳定发布版本，并继续固定完整提交 SHA：

| Action | 版本 | 上游声明的运行时 |
| --- | --- | --- |
| actions/checkout | v7.0.1 | node24 |
| actions/setup-java | v6.0.1 | node24 |
| actions/setup-node | v7.1.0 | node24 |
| gradle/actions/setup-gradle | v6.4.0 | node24 |
| android-actions/setup-android | v4.0.4 | node24 |
| actions/upload-artifact | v7.0.2 | node24 |
| actions/download-artifact | v8.0.2 | node24 |

Node 24 Action 至少需要支持该运行时的 Actions Runner；官方迁移文档给出的最低版本为 v2.327.1，发布仍使用 GitHub 托管的 ubuntu-24.04。JDK 21、脚本 Node 22、SDK 36、Build Tools 35.0.0 及仓库 Gradle Wrapper 保持原有版本。setup-node 显式设置 package-manager-cache: false，避免新版包管理器自动检测开启共享缓存；setup-gradle 保留 cache-disabled: true，不将签名相关构建状态写入共享缓存。

原生 DoH 接入后，云端 SDK 安装同时包含 NDK 27.0.12077973 与 CMake 3.22.1 / Ninja；编译前通过 runner 已有 rustup 显式安装 Rust 1.96.1 及 aarch64-linux-android 标准库，版本与已验收本机构建一致。原生测试使用 Cargo.lock，随后保留完整 JVM / Lint 与正式 R8 门禁。2026-10-12 的 [v0.2.0 首轮运行](https://github.com/woliubang256/kudo-android/actions/runs/38167524220)因 NDK 未安装在 buildDoh 阶段失败，尚未执行 JVM 测试；日志为 `artifacts/release/0.2.0/cloud-repair/failed-job-114553072702.log`。针对实际工作流编写 SDK 包、Rust 目标与安装失败即停止、原生测试顺序的回归测试，不能用本机已安装环境替代 CI 准备步骤。

工具链修复的 3 项回归先全部失败（`cloud-repair/toolchain-repro-tests.txt`），补齐后全部通过（`toolchain-fixed-tests.txt`）；actionlint 与 89 篇笔记检查通过。此时仅证明工作流准备契约，真实 Ubuntu 编译与发布仍由重推 Tag 后的云端运行验收。

依赖缓存采用 actions/cache v6.1.0 的 restore / save 子动作，固定官方完整 SHA `55cc8345863c7cc4c66a329aec7e433d2d1c52a9`（已读取两份 action.yml，均为 node24）。仅缓存 Gradle modules-2 / Wrapper 分发、Cargo registry / git 与 npm 内容缓存，以锁文件、Gradle 文件和 package.json 哈希失效并允许恢复同平台旧依赖。另按确切版本缓存 NDK、CMake 和 Rust 工具链；SDK 位置从 runner 环境读取。原生工具准备成功即保存，依赖缓存在完整检查成功、临时签名清理后保存。GitHub 缓存不可覆盖，失败时保存不完整的依赖会永久占住同一键；完整依赖使用 v2 键，同时允许恢复已有 v1 内容补齐后再保存。禁止缓存整个 ~/.gradle、配置缓存、daemon 日志、构建输出、签名文件、Cargo 凭据或 npm 配置，setup-gradle 的通用缓存继续关闭。缓存只加速已锁定依赖下载，命中后仍执行测试、签名和打包校验。

缓存白名单、锁文件失效/回退和失败路径保存的两项测试先复现缺失（`cloud-repair/cache-repro-tests.txt`）。修复后 35 项全部发布工具测试通过（`all-tool-tests.txt`，14.483 秒），两份工作流 actionlint 与 89 篇笔记检查通过。用户明确要求修复后删除远端 v0.2.0 再推送同名 Tag；原 Tag 对象为 `755cbfc6c0ea341616256b54085dc76a72bf7ef7`，指向 `845ad45238c176295f55159139a734ffb511c230`，仅在远端仍为该对象时替换，不修改其他版本。

重推后的运行 38202433589 已通过原生工具安装并写入缓存，但发布工具的 merge 夹具在 TemporaryDirectory 清理时报 objects 非空（`cloud-repair/job-114658524057.log`），符合 Git 自动维护后台写入与目录删除的竞态。测试临时仓库显式禁用 gc.auto 和 maintenance.auto，不修改开发者或 runner 的全局 Git 设置，不吞掉清理异常；增加本地配置回归，避免将重跑偶然通过视为修复。

GitHub 缓存遵循 ref 可见性：仅在 v0.2.0 内写入不能供后续 v0.2.1 直接复用。因此同一工作流也检查 main 推送并建立默认分支缓存，依赖键不包含 ref / SHA，后续 Tag 可恢复 main 的工具链和依赖。main 仅在缓存键输入（Gradle 脚本、Wrapper 配置、原生 Cargo.lock、package.json）或发布工作流改变时触发，普通 App 源码、测试或文档推送不启动该构建；路径过滤不限制 Tag，Tag 仍执行完整门禁。main 仅运行原生测试、JVM 与 Lint，不恢复私钥、不构建正式包、不生成发布日志或发布 Release；所有签名、公开产物与 publish job 都显式限制为 Tag。针对分支触发、路径白名单与缓存输入一致、逐项发布门禁增加回归，避免为了缓存意外在每次 main 推送时编译或发布。

Git 配置回归先失败（`cloud-repair/git-fixture-repro.txt`），修复后 36 项通过；默认分支缓存与签名门禁回归先失败（`main-cache-repro-tests.txt`），修复后最终 38 项全部发布工具测试通过（`final-tool-tests.txt`，13.724 秒）。actionlint 与 89 篇笔记检查通过；重推同名 Tag 后继续核对实际缓存命中、Ubuntu 原生 / JVM / Lint、正式签名和公开附件。

运行 38202802087 已实际恢复 827 MiB 工具链缓存，Rust 测试和 Android 原生库编译通过，但 native_notices 的一致性检查失败（`cloud-repair/job-114659667101.log`）。脚本原先直接排序绝对 Path：Windows 不区分大小写、Linux 区分大小写，根 LICENSE 与嵌套 aws-lc/LICENSE 的次序不同。许可文件按输出中的相对标签 casefold 排序，不能依赖宿主路径比较或源码目录位置；保留完整文本、依赖锁及陈旧检查。相同许可夹具分别采用两种 Path 比较规则生成，直接比较最终字节并确认三份许可均保留。

跨平台排序夹具先失败（`cloud-repair/notices-repro-tests.txt`），修复后 39 项发布工具测试全部通过（`notices-fixed-tests.txt`，10.232 秒）；本机原有 118 个组件 / 974079 字节许可包的 --check 通过，资产未重写。两份工作流 actionlint 与 89 篇笔记检查通过。首轮依赖缓存也已保存，后续重推继续检查完整 Ubuntu 构建及发布。

运行 38203795241 的 JVM / Lint、R8、正式证书和 0.2.0 / 2001 打包校验全部通过，但在日志步骤遇到 CLI 明确拒绝 --slurp 与 --jq 同用（`cloud-repair/job-114662704882.log`）。gh 只负责分页返回 JSON，现有 Python 日志脚本读取 JSON 页选择上个公开、非预发布且非当前 Tag 的版本；查询失败仍停止，不以失败 Tag、草稿或空文件冒充基线。不新增 jq 工具依赖，基线选择和真实 Python CLI 用离线多页夹具验收，shell 夹具同时模拟 CLI 参数冲突与 API 失败。

工作流将 `gh api --paginate --slurp` 的结果写入 runner.temp 的 `published-releases.json`，通过 `--published-releases` 交给 Python；API 失败由 Bash 的错误门禁停止。加强 shell 夹具后，旧工作流在已有发布和首次发布两种场景均按真实 CLI 报错失败（`cloud-repair/gh-cli-arguments-repro.txt`），修复后检查 JSON 原样传递及查询失败不调用生成器；Python 基线选择和 CLI 的多页、草稿、预发布、当前 Tag、无历史及畸形输入回归继续保留。

CLI 修复后 41 项发布工具测试全部通过（`cloud-repair/gh-cli-arguments-fixed.txt`，15.539 秒），两份工作流 actionlint 与 89 篇笔记检查通过。已认证 API 确认 main 的完整 v2 依赖缓存已保存 556680498 字节，原生工具链缓存为 867191363 字节；最终 Tag 运行继续验收实际恢复及公开附件。

main 路径过滤回归先复现无条件分支触发（`cloud-repair/main-paths-repro.txt`），收窄后 42 项发布工具测试通过（`main-paths-fixed.txt`，15.928 秒），actionlint 与 89 篇笔记检查通过。用户确认普通提交不应重复编译；本轮重复 main 运行 38205319317 已取消，保留 Tag 运行。运行 38205318965 实际命中 827 MiB 原生工具和 531 MiB 完整依赖缓存，Rust 11 项测试通过；311 项 JVM 中楼层并发测试超时，发布未执行，后续依实际报告修复，不绕过门禁。

最终[运行 38206575089](https://github.com/woliubang256/kudo-android/actions/runs/38206575089)的 build / publish 均成功，42 项发布工具测试、11 项 Rust 测试、完整 JVM / Lint 和签名 R8 门禁通过；Android 检查 9 分 30 秒，正式构建 6 分 40 秒。工具链命中 827 MiB，依赖命中 main 的完整 v2 缓存 531 MiB。远端 v0.2.0 注释 Tag 对象 `f73533540cd58e5a1d0b228e3724dfd10f6c11d1` 指向 `3c209ac712ded97e03690427daac9ba662a7fb9b`；本次工作流修改触发的重复 main 运行 38206575138 已取消。

[v0.2.0 Release](https://github.com/woliubang256/kudo-android/releases/tag/v0.2.0) 于 2026-10-12 05:26:14 UTC 发布，非草稿。重新下载的 `kodo_0.2.0_release_arm64-v8a.apk` 为 9121314 字节，SHA256 为 `db6f8abed07656c44cc6d50d9f779a520f970d1ee89d0e4cdefb894dae87048f`，与下载的摘要文件一致；正式证书 SHA256 为 `457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418`。包名、0.2.0 / 2001、单 arm64-v8a、非 debuggable、16KB ZIP / ELF LOAD 对齐全部通过，Release 正文与已审查的 App-only 日志一致。附件、正文与验收保存在 `artifacts/release/0.2.0/cloud-repair/published/`，构建及发布日志为同目录上一层的 `job-114671263366.log` / `job-114675392669.log`。云端与本机 APK 字节不同，不声明跨平台字节可复现；本次未安装或清理手机应用。

新版上传支持不压缩的单文件制品，但发布需要 APK、摘要和正文三个文件，因此显式使用 archive: true；下载显式使用 skip-decompress: false，让文件继续直接解压到 dist。digest-mismatch: error 使 Actions 制品摘要不一致时立即失败，发布 shell 仍单独核对 APK 的 SHA256。Tag 触发、权限拆分、临时密钥清理和草稿上传完才公开的契约保持一致。

## Related decisions

检索现有笔记：[构建入口](2026-10-07-android-build.md)部分重叠，保留 Wrapper、AGP、JDK 与签名不入库的决定并互链；[客户端验证](../testing/2026-10-07-client-verification.md)部分重叠，保留真机与测试夹具边界。其他 UI、账号与头像笔记不承载发布流程，不归档或覆盖旧决策。

## Alternatives considered

- 继续使用机器默认证书并手工选择正式密钥：配置最少，但签名依赖机器且容易误发未签名包；使用独立、固定的私有配置与Release构建门禁。
- 将密钥库提交Git：新机器和CI无需配置就能复现签名，但会公开长期私钥，且违反现有构建约定；仅提交配置示例，CI使用Secrets。
- 用GitHub run_number作为versionCode：重跑和构建次数容易递增，但依赖工作流历史，不能由Tag独立复现；使用有范围校验的三段版本映射。
- 同时支持任意预发布Tag：适合复杂发布通道，但需要额外约束alpha、beta、rc及正式版的安装升级顺序；当前只支持已批准的三段正式版本。
- 保留旧 Action 并依赖 GitHub 强制切换 Node 24：改动最少且 v0.1.5 已在兼容模式下发布成功，但上游仍声明已弃用的 Node 20，不能消除运行时和 setup-java 的版本弃用问题；升级到官方声明 node24 的稳定版本，不通过强制环境变量掩盖警告。
- 重新开启 setup-gradle 的整个用户目录缓存：能同时复用编译脚本和更多构建状态，速度收益更大；本项目同一作业稍后接触正式签名，选择公开依赖目录白名单，避免持久化潜在签名相关状态。
- 移除 setup-android，直接使用 runner 的 sdkmanager：可以减少一个第三方 Action，但需要自行维护命令行工具、许可证和安装路径；上游 v4.0.4 已支持 Node 24，保留原有 SDK 安装接口。
- 将 gh 的 JSON 管道交给独立 jq：表达式简短且 Ubuntu 通常自带 jq，但会新增脚本工具依赖并将基线规则放到 shell；复用已有 Python 日志生成器解析分页 JSON，保持可离线验证。
- 对 main 的每次推送都运行完整构建：能在打 Tag 前发现源码回归并补齐缓存，但源码或文档变化不会改变依赖键，和 Tag 同推还会重复编译；按用户确认收窄 main 为依赖配置或工作流变化，完整发布检查仍由 Tag 执行。

## Verification

2026-10-11：使用 `-PversionName=0.1.8 -PversionCode=1009` 完成正式 R8 构建，默认 Gradle 版本保持 `0.1.0 / 1`。正式包为 `artifacts/release/0.1.8/kodo_0.1.8_release_arm64-v8a.apk`，SHA256 为 `9acd96dff6cdddf78f533e782104c868c00ce00d019db7b78f1c5e0cdf850c49`；正式证书与既有发布一致，版本 `0.1.8 / 1009`、仅 `arm64-v8a`、非 debuggable 及16KB zipalign检查通过，详情为同目录 `local-verification.txt`。27项发布工具测试及完整290项JVM测试通过；主页旧夹具遗漏分类目录请求的问题及修正见[公开主页协议](../architecture/2026-10-10-public-profile-data.md)。本次发布前的既有UI与Lint结果见[加载圆环](../feature/2026-10-10-in-app-web-links.md)；本地验签不替代云端附件验收，不覆盖手机已安装的开发版。

本次发布脚本测试覆盖 `v0.1.6` 到 `1007`、`v0.1.7` 到 `1008` 的映射，覆盖 `0.1.9` 到 `0.2.0` 的进位、新小写 APK 名称，以及缺失、多 ABI 和非 `arm64-v8a` 清单的拒绝路径。

2026-10-11：使用 `-PversionName=0.1.6 -PversionCode=1007` 完成正式 R8 构建，`prepare_release.py` 生成 `kodo_0.1.6_release_arm64-v8a.apk` 及校验文件。`aapt` 显示包版本为 `0.1.6/1007` 且只含 `arm64-v8a`；`apksigner` 显示单一签名者、V2 验签通过且没有 debuggable 标记。APK SHA256 为 `7e4898ae810ecd9d7ee6a6da278f9975024b8facb78808079200af3f4b9a7d7c`。

2026-10-11：使用 `-PversionName=0.1.7 -PversionCode=1008` 完成正式 R8 构建，`prepare_release.py` 生成 `kodo_0.1.7_release_arm64-v8a.apk` 及校验文件。`aapt` 显示包版本为 `0.1.7/1008` 且只含 `arm64-v8a`；`apksigner` 显示同一正式证书、V2 验签通过且没有 debuggable 标记。APK SHA256 为 `40d57964f5dbe45938e7f6f73efd772b717515b532988dfba7a0098828631a20`。

2026-10-08 本地验证：

- Debug 与开启 R8 的 Release 均构建成功，通过 apksigner 验证并匹配各自独立的 RSA 4096 证书。真实重复生成后对比所有签名文件哈希，原文件不变；Git 忽略生效，已跟踪及未忽略文件中没有生成的密码。
- python -m unittest discover -s scripts/tests -v 的 15 项测试通过，覆盖覆盖保护、无效 Base64、Tag 格式和范围、组件进位后的版本排序、GitHub 输出注入、错误签名和 Manifest、校验失败不得产生发布文件。
- 四项真实 Gradle 配置检查通过：缺失 Release 签名、部分配置、密钥文件不存在均失败；空配置仍允许默认 Debug 构建。
- 按 CI 顺序使用空本地签名配置运行 testDebugUnitTest 与 lintDebug，检查成功；57 项 JVM 测试结果为零失败，本次复用 Gradle 判定未改变的测试结果；Lint 为 0 errors / 28 warnings。再由 Base64 临时恢复真实 Release 密钥，仅使用环境变量完成版本 0.1.0 / 1001 的 R8 构建（1分2秒）、验签及打包，临时密钥已清理，原始密钥哈希不变。
- 对真实 Debug APK、篡改后的 Release APK、与 Tag 不一致的版本执行发布脚本，三者均被拒绝且未生成发布文件。签名 APK 和校验文件位于 artifacts/release/，过程日志在 artifacts/release-validation/；这些目录不入库。
- release.yml 通过 actionlint 1.7.12。提取真实发布 shell、用本地 gh 替身验证新版本、遗留草稿、已公开版本、上传失败及校验失败五条路径；没有调用远程 GitHub。日志在 artifacts/publish-validation/。
- PowerShell 中包含路径的 -P 参数须整体加引号；未引用的路径在测试命令中被拆分，修正调用后通过。README 示例保留整体引号。

2026-10-08 远程接入验证：

- 私有仓库 woliubang256/kudo-android 已连接，main 包含发布工作流；GitHub API 确认 Actions 已启用，Android Release 工作流处于 active。
- GitHub CLI 2.102.0（下载包经官方 SHA256 校验）写入四个 Repository Secrets。写入前重新导出并核对本地正式证书，写入后通过 API 确认四项 Secret 名称均存在；没有输出密钥、密码或 Secret 值。实际云端取用与签名仍待首次 Tag 构建验证。
- origin 内嵌凭据已迁至 Windows Credential Manager，存储后的回读比对与私有仓库 ls-remote 认证均通过，fetch / push URL 均不再包含用户信息。最初远程检查曾意外输出内嵌令牌，已告知用户撤销并更换；文档和提交不保存该令牌。
- 接入阶段当时未创建版本 Tag、触发发布运行或创建 GitHub Release；仓库接入与 Secrets 配置不等于线上发布验收。

2026-10-11 首次真实 Tag 发布验证：

- 用户确认0.1.5真机修复有效并授权远程Tag与打包发布。main与annotated Tag `v0.1.5` 原子推送，Tag固定指向 `b3c072c5e92e41f3e8d17b05be7bb4f56d1ab3b7`；后续验收笔记提交不移动Tag。
- [Android Release运行38074297223](https://github.com/woliubang256/kudo-android/actions/runs/38074297223)首轮245项JVM测试中，TopicFloorNavigationTest.aSupersededJumpCannotReplaceTheLatestTarget发生5秒等待超时，签名与发布均被跳过。本地独立6项及完整245项JVM测试、Lint通过；未改代码或测试，重跑同一失败构建。第二轮20项发布工具测试、笔记、完整JVM测试与Lint通过；检查构建7分4秒，正式R8构建5分55秒，build与publish均为success。此结果没有确定首次超时根因；再次出现应先保留具体测试报告并排查调度与等待条件，不能把一次重跑通过视为稳定性修复。
- [v0.1.5 Release](https://github.com/woliubang256/kudo-android/releases/tag/v0.1.5)已发布且不是草稿或预发布，认证latest API指向该版本。附件为 `Kudo-0.1.5-release.apk` 与同名 `.sha256`，正文与本地按Tag提交生成的首发日志逐字匹配（忽略尾部空白）。
- 从Release重新下载实际附件后，摘要、apksigner、aapt与16KB zipalign通过：版本0.1.5 / 1006，非debuggable，APK为7,893,508字节；SHA256为 `dea55876a0f537e2c1b1aa8bb73ec6c134fea5dcda9d100fe5665fa312a52aed`，正式证书SHA256为 `457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418`，与原本机正式证书一致。云端和本机APK字节摘要不同，不声明跨平台构建字节可复现。
- 云端附件、正文与校验记录保存于 `artifacts/release/0.1.5/cloud/`；首轮失败日志为 `remote-release-build-failure.log`，第二轮日志为 `remote-release-build-success.log` 与 `remote-release-publish-success.log`。GitHub访问凭据仅用于已有Credential Manager认证和API请求，下载重定向离开api.github.com时移除Authorization，不写入附件、日志或仓库。

2026-10-11 Action 运行时升级本地验证：

- 修改前从固定 SHA 读取七个官方 action.yml，运行时检查全部因 node20 失败；修改后同一检查全部通过，逐一确认声明 node24、工作流参数受支持，固定 SHA 与官方稳定版本 Tag 的实际提交一致。只读检查脚本与前后结果分别保存在 `artifacts/verify_actions_runtime.py`、`actions-runtime-before.json`、`actions-runtime-after.json`，不引入 CI 网络测试依赖。
- 实际 `.github/workflows/release.yml` 通过 actionlint 1.7.12；20 项发布工具测试通过，包含从该工作流提取发布 shell 后验证的新建草稿、恢复草稿、已公开版本拒绝覆盖、上传失败和摘要失败路径。没有改动 App 源码，因此不重复无关的 JVM、Android Lint 或真机测试。
- 本次仅修改工作流和现有发布笔记，并按用户确认推送 main；不创建新发布 Tag，不移动已发布 v0.1.5，不把本地校验视为新 Action 的云端发布验收。

2026-10-11 v0.1.8 发布尝试：

- 本地完整290项JVM、27项发布工具测试与正式包验签通过后，main和annotated Tag `v0.1.8` 原子推送，Tag固定指向 `921392bb330f9d231ee502d1501d38df3013d353`；后续记录只推main，不移动Tag。
- [Android Release运行38150590635](https://github.com/woliubang256/kudo-android/actions/runs/38150590635)的发布工具与笔记检查通过，但云端290项JVM出现两项失败：FeedViewModelTest.categoryRateLimitDoesNotReplaceSuccessfulFeedWithError 在测试启动时报 UncaughtExceptionsBeforeTest；TopicFloorNavigationTest.aSupersededJumpCannotReplaceTheLatestTarget 再次出现5秒等待超时。签名构建与publish均未执行，没有创建v0.1.8 Release。完整构建日志为 `artifacts/release/0.1.8/remote-build-failure.log`，状态为同目录 `remote-run.json`。当前只有日志摘要，尚无云端XML中的被抑制异常及具体等待阶段，不能把两项失败归因于确定的App缺陷或确定的夹具缺陷；不以盲目重跑替代排查。
- main的Commit messages校验成功；[tag校验运行38150590659](https://github.com/woliubang256/kudo-android/actions/runs/38150590659)失败。新Tag事件的before全零，工作流使用单个SHA调用git log，误把所有旧祖先提交纳入前缀门禁，和仅约束新提交的既有约定不符。失败日志为 `tag-commit-check-failure.log`。历史提交与Tag均不改写；该CI范围问题另行修复。
- 本地 `kodo_0.1.8_release_arm64-v8a.apk` 及校验文件保留，未覆盖手机开发版。版本升级、打包、Tag与推送已完成，自动Release未完成；当时等待用户选择是否另发0.1.9，未自行再升版本。

2026-10-11 v0.1.9 发布准备：

- 用户确认修复后发布0.1.9 / 1010，保留v0.1.8 Tag不变。Tag前缀校验的实际shell夹具先复现旧历史误扫，修复后覆盖有效增量、非法中间提交及首次Tag；取消清理用例先复现夹具提前关闭，修正等待清理和串行Main后21项相关用例、两核同一JVM连续10轮共210项通过，详细证据见[客户端验证](../testing/2026-10-07-client-verification.md)。完整291项JVM及Lint通过，Lint为0 errors / 38 existing warnings / 1 hint，日志为 `artifacts/release-0.1.9-unit-lint.txt`；30项发布工具测试通过，日志为 `release-0.1.9-tool-tests.txt`。两份工作流均通过actionlint，失败时上传完整JUnit报告，不绕过失败门禁。
- 更新日志支持已发布基线并拒绝非法/非祖先范围，实际shell夹具验证空基线与API失败停止；自动流程查询上次已公开Release，避免v0.1.6、v0.1.8失败Tag漏掉App变更，仍过滤文档、测试、工具链和CI。
- 使用 `-PversionName=0.1.9 -PversionCode=1010` 完成正式R8构建，默认Gradle版本保持不变。`artifacts/release/0.1.9/kodo_0.1.9_release_arm64-v8a.apk` 的SHA256为 `0a6f4830e227876fef62ed2ed90764e646136d9c47922d08393ba4241b1ce2ca`；正式证书与既有发布一致，版本0.1.9 / 1010、仅arm64-v8a、非debuggable及16KB zipalign通过，详情为同目录 `local-verification.txt`。构建日志为 `artifacts/release-0.1.9-build.txt`，本地验证不代替云端附件验收。已认证API确认上个已公开版本仍为v0.1.5，更新日志预览使用该基线。

2026-10-13 v0.2.4 发布准备：

- 用户要求先打本地正式包、再升版本并推送Tag。先以最新功能代码构建0.2.3 / 2004，保留在 `artifacts/release/0.2.3/local-current/`；它包含已公开v0.2.3之后的改动，不能冒充该Tag的原附件，SHA256为 `9ec321e0f326ccfdb5787dc9f4389e6e56514d86f57ff98c989ddcb091f67402`。
- 随后按现有Tag映射构建0.2.4 / 2005，默认Gradle版本继续保留0.1.0 / 1。正式包 `artifacts/release/0.2.4/kodo_0.2.4_release_arm64-v8a.apk` 为9,224,550字节，SHA256为 `f50a7c9c5b16cb2d892706208dc2713b870c838b77936d56632a336fb9106085`；正式证书与既有Release一致，版本、仅arm64-v8a、非debuggable、ZIP及ELF 16KB对齐验证通过，证据为同目录 `verification.json` 和 `verification.txt`。两个正式包均未安装覆盖手机开发版。
- API确认当前已公开基线为v0.2.3，`release-notes/v0.2.4.md` 将10条App候选合并为2条新增、3条修复；面板限高与升级弹窗修复合并，三个点赞/草稿提交合并，三个原图容量修复合并。HEAD来源校验通过，原始候选保留在 `artifacts/release/0.2.4/release-notes.raw.md`。此处只记录本地准备，云端发布及实际附件另行验收。

2026-10-13 v0.2.4 云端发布验收：

- 首轮运行38362477072在完整357项JVM中出现两项时序夹具失败，正式构建与发布未执行。[客户端验证](../testing/2026-10-07-client-verification.md)记录原始报告、作者请求计数的确定性复现、可控取消回调修正、两核110项重复验证及完整357项JVM和Lint通过。未改App运行时行为，更新日志来源保持有效。
- 修复提交1660e09后替换尚未发布的v0.2.4 Tag，并原子推送main与Tag；[运行38364386746](https://github.com/woliubang256/kudo-android/actions/runs/38364386746)的build和publish均success，完整JVM、Lint和正式R8构建通过。[v0.2.4](https://github.com/woliubang256/kudo-android/releases/tag/v0.2.4)于北京时间2026-10-13 15:52:55公开发布，非草稿、非预发布，latest API指向该版本。发布后补记仅推main，不移动该Tag。
- 下载实际附件到 `artifacts/release/0.2.4/published/`：APK为9,223,822字节，SHA256为 `9658b26d5a486d574b2a9684516d04d1b9616eb77483b059f764aa2fdac18092`，与发布校验文件一致。正式证书、0.2.4 / 2005、仅arm64-v8a、非debuggable、ZIP及ELF 16KB对齐全部通过，正文与已整理日志逐字匹配；证据为该目录 `verification.json`，云端成功日志保留在同级 `cloud-success/`。本地包与Linux云端包分别验签，不要求跨环境二进制相同；手机开发版及其数据未变动。

## Consequences

签名与版本检查使发布包能保持更新身份并对应 Tag；代价是开发者必须备份 signing/ 和 signing.properties。丢失正式证书或密码后无法使用原签名更新已安装应用。Debug 和 Release 保持相同包名、使用不同签名，不能互相覆盖安装；当前手机仍保留旧 Debug 签名应用，本次未卸载或替换，也未把构建和验签当成真机运行验收。

远程仓库为 [woliubang256/kudo-android](https://github.com/woliubang256/kudo-android)，私有仓库的 main 已包含工作流，GitHub Actions 已启用，Android Release 工作流处于 active；接入使用本地已生成的正式证书配置四个 Repository Secrets。v0.1.5已完成真实Tag触发、云端构建和Release附件验收。仓库仍为private，本次通过已认证API验收；公开仓库后仍需单独验证匿名latest API与App的更新检查，不能将认证访问通过当成公开可访问。

关闭共享缓存使首次云端构建更慢，且依赖 Google Maven、Maven Central、Android SDK 及 GitHub 服务。稳定三段版本暂不支持 alpha/beta/rc；需要这些通道时须先设计安装升级顺序。

Action 升级跨越多个主版本，本地静态校验及发布 shell 夹具不能替代托管 runner 上的真实 SDK 安装和制品传递。修正仅推送 main，不创建或移动发布 Tag；重跑 v0.1.5 仍使用旧 Tag 的工作流。下一次新版本 Tag 发布需要确认 build、publish 不再出现这两类弃用警告，并重新验签和校验附件摘要；现有首次 JVM 超时问题没有在本次依赖升级中修复。
