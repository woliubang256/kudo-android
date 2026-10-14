# 构建与安装

[返回 README](../README.md)

以下命令均在仓库根目录运行。命令示例使用 Windows PowerShell；macOS / Linux 的 Gradle 命令改用 `./gradlew`。

## 安装正式版

从 [GitHub Releases](https://github.com/woliubang256/kudo-android/releases/latest) 下载 `kodo_<version>_release_arm64-v8a.apk`，在手机上打开安装。当前仅支持 arm64-v8a，最低 Android 8.0。

覆盖安装需要包名和签名一致。开发版与正式版签名不同；需要保留开发版数据时，请继续使用原签名构建并通过 `adb install -r` 更新，不要为解决签名冲突卸载应用或清除数据。

## 本地构建

需要 JDK 17+（CI 使用 JDK 21）、Android SDK Platform 36、Rust（CI 使用 1.96.1）、Python 3.10+，以及 Android NDK 27.0.12077973、CMake 3.22.1 / Ninja。首次构建需要访问 Google Maven、Maven Central、Gradle 与 crates.io。设置 `ANDROID_HOME` 或在未入库的 `local.properties` 中填写 `sdk.dir`。仓库包含 Gradle Wrapper，不需要全局安装 Gradle。

先运行 `rustup target add aarch64-linux-android`。Gradle 自动以锁定依赖编译 `native/doh` 的 arm64 库、校验 16KB 对齐与随包许可；源码未变时复用生成库。原生代码及产物均只支持 arm64-v8a，手机不需要 Rust 环境。更新原生依赖后运行 `python scripts/native_notices.py` 同步许可。

```powershell
.\gradlew.bat :app:assembleDebug
adb devices -l
adb -s <真机序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <真机序列号> shell am start -n com.kudo.android/.MainActivity
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。没有私有签名配置时，源码克隆使用 Android 默认 Debug 证书。

覆盖安装或运行真机测试前，先核对版本和签名。`app/build.gradle.kts` 的本地默认值是 `versionName=0.1.0`、`versionCode=1`；如果真机已安装更高版本，必须用不低于已安装版本的参数构建，例如：

```powershell
adb -s <真机序列号> shell dumpsys package com.kudo.android | Select-String 'versionCode=|versionName='
.\gradlew.bat :app:assembleDebug "-PversionName=0.2.4-dev" "-PversionCode=2005"
adb -s <真机序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

签名和版本任一不匹配时先修复构建参数；不要卸载应用或清除数据来绕过安装错误。
## 本地签名

首次初始化固定签名（需要 Python 3.10+ 和 JDK 的 `keytool`）：

```powershell
python scripts/generate_signing.py
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

生成器创建独立的 RSA 4096 / PKCS12 Debug、Release 证书，有效期 10000 天；已有 `signing/` 或 `signing.properties` 时拒绝覆盖。Gradle 自动读取根目录的私有 `signing.properties`，分别输出 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/release/app-release.apk`。Release 开启 R8，缺少完整签名时构建失败。

请把 **`signing/` 与 `signing.properties` 一起私密备份**。其中 `kudo-debug.p12` / `kudo-release.p12` 是密钥库，`.pem` 是公开证书，`certificates.json` 记录 SHA256 指纹。密码只保存在本机配置中；这些文件均被 Git 忽略。正式签名丢失后无法继续覆盖更新相同签名的已安装应用。

已有密钥可参考 [signing.properties.example](../signing.properties.example) 填写；路径相对仓库根目录，也可使用绝对路径。`"-PsigningPropertiesFile=<路径>"` 可切换配置文件，PowerShell 中请对整个参数加引号。环境变量优先于配置文件，Debug / Release 分别支持以下同名后缀：

| 配置项 | Debug 环境变量 | Release 环境变量 |
| --- | --- | --- |
| 密钥库路径 | `DEBUG_KEYSTORE_PATH` | `RELEASE_KEYSTORE_PATH` |
| 密钥库密码 | `DEBUG_STORE_PASSWORD` | `RELEASE_STORE_PASSWORD` |
| 别名 | `DEBUG_KEY_ALIAS` | `RELEASE_KEY_ALIAS` |
| 私钥密码 | `DEBUG_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` |
| 格式（默认 PKCS12） | `DEBUG_KEYSTORE_TYPE` | `RELEASE_KEYSTORE_TYPE` |

Debug 与 Release 保持相同包名、使用不同签名，不能互相覆盖安装。新生成的 Debug 证书也与此前机器默认签名不同；需要保留手机数据时，请继续使用原签名构建，不要直接卸载旧应用。

## Tag 自动发布

[release.yml](../.github/workflows/release.yml) 在推送 `vX.Y.Z` 时运行：脚本测试、笔记检查、Rust 测试、JVM 测试和 Lint 通过后恢复正式签名，构建 Release，核对证书、包名、版本及不可调试标记，生成 SHA256 文件，再发布 GitHub Release。构建作业只有仓库读取权限；独立发布作业使用 `GITHUB_TOKEN` 写入 Release，无需额外的长期发布令牌。

构建依赖按 Gradle 脚本、Wrapper 配置、`native/doh/Cargo.lock` 和 `package.json` 的内容哈希缓存；NDK、CMake 与 Rust 工具链按确切版本缓存。相同配置复用已有缓存，依赖变化或缓存被清理时重新下载。`main` 仅在依赖配置或发布工作流变化时执行缓存构建，普通源码、测试和文档提交不触发 Android 构建；版本 Tag 始终执行完整发布检查。

在 **Settings → Secrets and variables → Actions** 添加以下 Repository secrets；本地与 Actions 应使用同一套 Release 证书：

| Secret | 本地来源 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `signing/kudo-release.p12` 的 Base64 内容 |
| `RELEASE_STORE_PASSWORD` | `signing.properties` 的 `release.storePassword` |
| `RELEASE_KEY_ALIAS` | `release.keyAlias`（生成器默认 `kudo-release`） |
| `RELEASE_KEY_PASSWORD` | `release.keyPassword` |

Windows 可将密钥库 Base64 直接复制到剪贴板，再粘贴到对应 Secret，避免把私钥输出到终端或写入仓库：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'signing/kudo-release.p12').Path)) | Set-Clipboard
```

当前已公开正式 Release 为 `v0.2.4` / `versionCode=2005`。本次先构建包含最新改动的本地 `0.2.3` / `2004` 正式包，再升级至 `0.2.4` / `2005`；新版本产物为 `kodo_0.2.4_release_arm64-v8a.apk` 和同名 `.sha256`，云编译及发布成功，实际附件已复核签名、版本和校验值。正式 Release 只包含 `arm64-v8a`。自动发布从上一个已公开正式 Release 到当前 Tag 的 commit 中筛选 App 运行时变化，失败 Tag 和草稿不截断更新日志，首次版本包含完整历史。新提交请使用 `feat:`、`fix:`、`perf:`、`doc:`、`test:`、`build:`、`ci:`、`chore:` 等前缀，冒号后写中文说明；只有 `feat:`、`fix:`、`perf:` 且实际修改 `app/src/main/` 运行时源码或资源的提交会进入更新日志，文档、测试、工具链、CI、许可证和合并提交被过滤。

发布前先由当前 AI 协作者整理日志，再提交并打 Tag。脚本提取原始候选和校验来源，AI 负责合并同一功能的多次提交及后续修复，写成“新增、修复、调整”下的简短中文条目；同一新增功能的配套修复并入新增条目，保留最终效果，省略开发、调试及测试过程。整理时必须对照 App 变更核实，避免遗漏或夸大。CI 直接读取已提交的版本文件，不调用模型，无需 AI API Key。

先确认上一个**已公开正式 Release**（例如通过 `gh release list --exclude-drafts --exclude-pre-releases` 查看），再从 HEAD 为尚未创建的 Tag 提取草稿：

```powershell
python scripts/release_notes.py --tag v0.2.4 --previous-tag v0.2.3 --prepare --output release-notes/v0.2.4.md
```

`--previous-tag` 不应填写失败 Tag 或草稿；首次发布使用 `--previous-tag=` 显式传空基线。AI 编辑 `release-notes/v0.2.4.md` 正文时保留第一行来源元数据、版本标题和末尾完整变更链接。文件已存在时脚本拒绝覆盖；需要重新整理时先提取到 `artifacts/` 下的新草稿，再核对并更新版本文件。

整理后，在打 Tag 前校验并预览，另存原始候选供核对：

```powershell
python scripts/release_notes.py --tag v0.2.4 --previous-tag v0.2.3 --from-head --prepared-notes-dir release-notes --raw-output artifacts/release-notes.raw.md --output artifacts/release-notes.md
```

来源校验跟踪筛选后的 App 提交，文档、测试、CI 和日志自身的提交不使其失效；新增 App 提交或已公开发布基线变化时必须重新提取并整理。已有文件的来源、格式、分类或版本链接不匹配会停止发布。没有版本文件时仅为历史或手动发布兼容而回退原始提交日志，不会自动完成 AI 精简。

检查预览后提交日志，确保代码和日志都已推送，再推送新 Tag：

```powershell
git add release-notes/v0.2.4.md
git commit -m 'doc: 整理0.2.4更新日志'
git push origin main
git tag -a v0.2.4 -m '发布 0.2.4'
git push origin v0.2.4
```

工作流把去掉来源注释的 `release-notes.md` 写入 GitHub Release，App 的“关于酷DO → 检查更新”显示该正文；原始候选 `release-notes.raw.md` 只保留在 Actions 构建产物中供核对。工作流先准备 Release 草稿、上传完整产物后再公开；上传失败留下的草稿可以重跑恢复，已经公开的同名 Release 不会被覆盖，应使用新版本 Tag。手动发布在创建 Tag 后去掉 `--from-head` 重新校验和输出日志，再用 `gh release create <Tag> --notes-file artifacts/release-notes.md` 发布。

检查更新只读取公开正式 Release，不使用私有仓库凭据；仓库私有或未发布时显示暂无公开版本。下载和查看源码使用外部浏览器。

## 提交信息与版本规则

提交标题使用 Conventional Commits 前缀，例如 `feat:`、`fix:`、`perf:`、`doc:`、`test:`、`build:`、`ci:` 或 `chore:`，冒号后写一句中文说明。新克隆仓库后启用本地校验钩子：

```powershell
git config core.hooksPath .githooks
```

钩子会拒绝没有受支持前缀的提交；`scripts/check_commit_history.py` 还会在 CI 中检查本次推送或 Pull Request 的提交范围。历史提交不会被自动改写，发布日志仍只提取实际修改 `app/src/main/` 的 `feat:`、`fix:` 和 `perf:` 提交。

`.github/workflows/commit-messages.yml` 只在 `main` 推送和 Pull Request 上检查提交信息，不再为已经属于 `main` 历史的版本 Tag 重复运行。`.github/workflows/cleanup.yml` 每周自动清理超过 30 天的已完成 Actions 运行和中转产物，也支持手动触发并指定保留天数（至少 7 天）；GitHub Release 中的 APK 附件不受影响。

版本名为 Tag 去掉 `v`，严格使用 `vX.Y.Z`，其中 `X`、`Y`、`Z` 各为 `0–9` 的一位数字；`versionCode = major × 1000000 + minor × 1000 + patch + 1`。例如 `v1.2.3` 对应 `1.2.3` / `1002004`，`v0.2.1` 对应 `0.2.1` / `2002`，本次 `v0.2.4` 对应 `0.2.4` / `2005`。补丁位从 `0.1.9` 进位到 `0.2.0`，大功能可提升 minor 或 major 位；前导零、`-rc`、`-beta` 等格式会失败。每次升级请使用更大的版本号。

普通本地构建保留 `0.1.0` / `1` 默认值，也可按 Tag 值构建相同版本：

```powershell
python scripts/release_version.py v0.2.1
.\gradlew.bat :app:assembleRelease "-PversionName=0.2.1" "-PversionCode=2002"
```

发布验收用例：有效 Tag 应产生版本和签名一致的 APK；非法 Tag、缺失 Secret、签名错误、APK 被修改或版本不符均应停止发布。本地检查通过后，还需确认 GitHub Actions 的构建和发布作业成功，并下载实际发布的附件核对签名、版本及 SHA256。

## 验证

```powershell
npm run verify-notes
python -m unittest discover -s scripts/tests -v
cargo test --locked --manifest-path native/doh/Cargo.toml --lib
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s <真机序列号> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 使用 Android CLI 检查过布局时，先停止它的辅助测试进程，避免 UiAutomation 冲突。
adb -s <真机序列号> shell am force-stop com.android.cli.interact.instrumentation
adb -s <真机序列号> shell am instrument -w -r -e class 'com.kudo.android.CategoriesUiTest,com.kudo.android.HomeRefreshUiTest,com.kudo.android.FeedHeaderUiTest,com.kudo.android.CommunityUiTest,com.kudo.android.SurfaceLiquidUiTest,com.kudo.android.SettingsModalUiTest,com.kudo.android.NavigationMaterialUiTest' com.kudo.android.test/androidx.test.runner.AndroidJUnitRunner
```

2026-10-08：37项 JVM 测试、37项 RMX2202 / Android13 真机界面测试通过（79.933秒），包括分类图标元数据、23种矢量与默认图标、分类跳转/重试、深色130%窄屏，以及既有首页和底栏回归。真实分类图标、页面清理及开发调优列表跳转已检查。Debug / 测试 APK、Lint 与笔记检查通过，Lint 为0 errors / 27 warnings。此前首页线上只读探针通过（21.297秒），真实访客推送与刷新已验证；登录私有频道仍需账号验收。此前材质慢拖录屏与关闭动画的28项结果保留。真机检查记录与手动验收用例见 [TASKS.md](../TASKS.md)。真实社区只做只读检查，自动化帖子与互动使用夹具。

线上探针 `LiveUpdatesProbeTest` 默认跳过，手动运行需同时传 `-e liveReadOnlyProbe true -e class com.kudo.android.LiveUpdatesProbeTest`。它等待最多60秒的真实可见更新，不作为离线回归或CI门禁。

## 项目结构与实现记录

原生代码位于 `app/src/main/java/com/kudo/android/`：`data/` 负责 Discourse 协议与本地设置，`ui/` 按信息流、话题、账号、编辑器拆分，`ui/components/` 封装玻璃材质与公共组件；`native/doh/` 提供 Rust DoH / ECH 网络通道。

重要取舍记录在 `.agents/notes/`。界面材质与交互实现可查阅[导航与按钮](../.agents/notes/implemented/architecture/2026-10-07-backdrop-demo-navigation.md)、[设置模态](../.agents/notes/implemented/feature/2026-10-08-glass-settings-modals.md)；第三方来源与许可见[第三方组件与参考](../THIRD_PARTY_NOTICES.md)。
