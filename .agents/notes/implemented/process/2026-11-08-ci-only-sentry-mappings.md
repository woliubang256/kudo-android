# Agent Note: 仅 CI 构建上传 Sentry 符号映射

Status: implemented

## Problem

错误上报已接入，但尚无对应 R8 mapping 上传。用户要求只有 CI 构建上传符号表，本地 Debug 和 Release 均不上传，并在改完后按线上版本构建本地正式包。

## Decision

实现分为 Gradle 插件边界、CI 接线与测试、文档及本地正式打包三个单元。

固定 Sentry Gradle 插件 6.26.0，只有 GitHub Actions 环境且显式传入 sentryCiUpload=true 才应用插件。默认不应用插件，本地即使存在上传凭据也没有上传或 UUID 生成任务；本地误传开关则直接拒绝。CI 的 JVM/Lint 与 main 缓存作业不传开关，只有 Tag 的签名 Release 构建传入。

只上传该 CI Release 的 R8 mapping，并由插件生成同一构建的 Proguard UUID 写入 APK，运行时白名单保留该 UUID。忽略 Debug，关闭自动 SDK 安装、性能插桩、运行时优化、源码上下文、原生符号、依赖报告、插件遥测、大小分析和分发上传，保留既有 Core SDK 与用户授权初始化。上传失败停止 Release，避免公开无法关联符号的安装包。

上传认证仅从 CI 步骤的 SENTRY_AUTH_TOKEN Secret 读取，组织及项目 slug 从 SENTRY_ORG / SENTRY_PROJECT 仓库 Variables 传入；缺配置只报告变量名称，不打印值。DSN 不具备上传权限。verifySentryBuild 检查真实插件/任务与隐私选项，用正常本地构建、误传参数与无上传的 CI 配置探针验收。

## Existing note audit

检索非归档 Sentry、mapping、符号和 CI 构建记录：[授权上报](../../implemented/feature/2026-11-08-opt-in-sentry-reporting.md)部分关联，保留运行时授权与过滤；[签名发布](../../implemented/process/2026-10-08-signing-and-tag-releases.md)保留签名、版本和仅 Tag 发布的边界，本方案只补充其构建步骤；[Actions 保留](../../implemented/process/2026-10-15-actions-retention-and-main-validation.md)无职责变化。无同主题重复或被完全取代的笔记。

## Testing

Gradle 插件只声明不自动应用，显式参数与 GitHub Actions 双条件控制应用。正常本地验证、模拟本机存在上传凭据的验证均通过，没有上传任务；本地误传开关按预期失败（artifacts/sentry-local-gate.txt、sentry-local-with-credentials.txt、sentry-local-misuse.txt）。

CI 只配置、不上传的探针先复现验证任务误查 Task 而非 Project 扩展的问题，改为 project.extensions 后通过（artifacts/sentry-ci-gate-probe.txt、sentry-ci-gate-fixed.txt）。凭据使用无效测试值，探针只执行验证任务；CI assemble 的任务关系使用 --dry-run 检查，不执行上传。

插件的 runtimeOptimizations 默认开启且独立于 tracing 开关；新增断言先复现默认生成运行时选项的问题（artifacts/sentry-runtime-options-repro.txt），显式关闭后验证通过（artifacts/sentry-ci-final.txt），并检查该配置及 generateSentryBuildTimeOptions 任务不存在。最终 CI dry-run 的 Sentry 任务仅包含 mapping UUID 生成、资产注入和 Release mapping 上传（artifacts/sentry-ci-release-final-taskgraph.txt）。该开关不能因仅关闭 tracing 而省略。

工作流与凭据前置检查的两项用例先失败于尚未接线（artifacts/sentry-workflow-repro.txt），接线后 10 项 ReleaseWorkflowTest 全部通过（artifacts/sentry-workflow-final.txt）。真实执行工作流 Bash，成功场景只调用夹具 Gradle；对 Token、组织和项目分别测试缺失、空串、纯空白，均在调用前停止且不打印 Token。

线上最新正式 Release 与已安装正式版均核对为 0.3.7 / 3008。本地显式以该版本执行 verifySentryBuild、assembleRelease、lintRelease，全部通过，没有应用插件或注册上传任务（artifacts/sentry-ci-local-release-build.txt）。打包源提交为 9b27e5a，正式 APK 与 SHA256、本地 mapping、签名/Manifest/ZIP 对齐证据保存在 artifacts/local-release-0.3.7-sentry-ci-9b27e5a/。核对既有 Release 证书、不可调试、仅 arm64-v8a、ZIP 完整性及全部原生库 16KB 对齐；APK 中 auto-init=false，无 Sentry Provider、mapping UUID 和 Sentry 原生库。本地 mapping 仅保存，未上传；未安装或发布该正式包。

## Alternatives considered

- 所有构建应用插件再关闭 autoUpload 可以共用 UUID 生成逻辑，但本地仍注册上传任务，未来修改或手动调用容易越过边界；只在 CI 显式应用整个插件。
- CI 直接调用 sentry-cli 可以完全控制上传命令，但还要自行生成并注入与 mapping 一致的 UUID，容易将缓存映射与错误构建关联；复用官方插件，并关闭本任务以外的能力。
- 只凭环境中存在上传 Token 判断最简单，但本机保存凭据时也会上传；同时要求 CI 环境与显式构建参数。

## Consequences

真实上传需要用户配置 Sentry 组织、项目与上传 Token；当前仅提供 DSN，不能宣称已完成远端上传。相同版本号的本地包与 CI 包具有不同构建来源，本地包不使用 CI mapping。只有 CI 自己生成的 UUID 才能关联其 mapping。插件会增加首次 Gradle 依赖解析，但本地不执行其逻辑。
