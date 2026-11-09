# Agent Note: 发布前用 AI 归并更新日志并随 Tag 保存

Status: implemented

## Problem

同一功能分多次开发或在验证后修复时，提交标题各不相同，逐条提取会把“新回复订阅、增量加载、前台自动监听”等内部步骤重复展示给用户。仅去重相同标题不能表达最终效果。

## Decision

用户选择发布前由当前 AI 协作者整理文件，云端直接读取，不接外部模型服务。继续通过原有发布基线、类型和 App 运行时路径过滤提取原始候选；新增 `--prepare` 从 HEAD 为尚未创建的版本 Tag 生成带来源元数据的 Markdown 草稿。AI 合并同一功能的新增及后续修复，保留简短中文的新增、修复、调整分组，不补写源码没有实现的能力。

App 运行时路径包括 `app/src/main/` 和随 APK 打包为 jniLibs 的 `native/doh/`（源码与 Cargo 清单），两处的 `licenses/` 均排除。2026-11-10 整理 v0.3.9 时发现 ldstatic.com 共用节点与 IPv6 偏好两条提交只改 `native/doh/`，被旧过滤静默漏掉；用户确认后纳入。以后新增其它打包进 APK 的原生目录需同步此前缀。

整理结果保存在 `release-notes/vX.Y.Z.md`，与发布代码一起提交后打 Tag。元数据包含目标版本、上个已公开版本和筛选后 App 提交的 SHA256 指纹；文档、测试和日志自身的提交不影响指纹，后续 App 提交或发布基线变化会使文件失效。云端读取 Tag 检出目录中的对应文件，验证元数据、版本标题、分组和完整变更链接，再使用正文；没有文件的历史或手动发布仍回退原始日志，已有但过期或无效的文件必须报错，避免误用。原始候选同时保留为构建产物，不公开进用户更新说明。

## Existing note audit

[提交日志](../../implemented/process/2026-10-11-commit-release-notes.md)部分重叠，保留提交筛选、发布基线和原始候选生成，本篇取代直接把提交标题作为最终说明的部分；其不在 CI 调用 LLM 的约束仍成立。[签名与 Tag 发布](../../implemented/process/2026-10-08-signing-and-tag-releases.md)部分重叠，签名、权限、草稿及产物发布顺序保持原契约。[更新检查](../../implemented/feature/2026-10-11-github-updates.md)部分重叠，继续读取同一 Release 正文。proposed 中没有同主题旧提案；未建立 rejected 目录，无过时提案或完全吸收项。

## Alternatives considered

- CI 调用模型可以自动归并，减少发布前操作；但需要额外服务、模型及凭据，失败或重跑时还可能改变措辞。用户明确选择发布前整理并提交文件，不采用云端模型调用。
- 只保留确定性的标题去重无需维护新文件，也能完全复现；但不能合并不同标题表达的同一功能，继续逐条展示无法满足本次要求，因此只作为原始候选和缺文件时的回退。
- 按路径或关键词自动归并不需要模型凭据；但同一源码文件可能有多项独立改动，同一功能也会跨文件，容易合错或漏掉用户可见差异，因此归并交给 AI 协作者审阅。

## Verification

三项 CLI 复现先失败，旧脚本没有准备草稿和使用已整理文件的入口（artifacts/prepared-release-notes-repro.txt）。17项日志脚本测试通过，临时 Git 仓库验证多次提交合并、原始候选保留、未打 Tag 时从 HEAD 准备、文档和测试不影响来源、后续 App 修复及基线变化过期、错误元数据/标题/比较链接/分类/重复项拒绝、已有文件不覆盖、无 App 变化不可凭空新增条目；原有基线、路径和类型过滤、实际发布 shell 的草稿及失败保护继续通过（artifacts/prepared-release-notes-script-tests.txt）。

日志工作流先补复现，证明旧命令未读取已整理文件、未保留原始候选，且无效或过期文件没有阻止生成（artifacts/prepared-release-notes-workflow-repro.txt）。接入后26项日志与工作流测试通过（artifacts/prepared-release-notes-workflow-tests.txt）：从实际 YAML 提取 shell，查询接口使用离线替身，日志命令运行真实 Python 脚本及临时 Git 仓库，验证摘要替换、无文件回退、错误分组拒绝、后续 App 提交使来源过期及原始候选保留。上传产物包含 `release-notes.raw.md`，公开 Release 仍仅上传 APK 和校验摘要并使用 `release-notes.md` 正文。actionlint 检查通过，本轮没有执行远端发布。

实际 v0.2.1 范围的14条 App 提交整理为8条说明：新回复的订阅、增量加载和前台监听合并为1条；正在回复名单、即时更新、头像显示和按钮宽度修复合并为1条；两个页面入口改名合并为1条。历史示例及原始候选留在 `artifacts/release-notes-ai-example/`，通过真实 CLI 校验；不改既有 Tag 或已公开正文。下一版 `release-notes/v0.2.2.md` 按 v0.2.1 基线保存当前三色图片加载修复并通过 HEAD 预览，后续 App 改动仍需重新整理。AGENTS 与构建文档记录准备、AI 编辑、预览、提交及打 Tag 的顺序。

## Consequences

`test_bundled_native_runtime_changes_are_kept` 在旧过滤上先失败，修复后19项日志脚本测试通过；改动过滤会改变来源指纹，已整理但尚未发布的版本文件需重新提取。

来源指纹只能证明整理时使用了当前候选范围，不能证明摘要语义完整，发布前仍需 AI 对照原始候选检查遗漏或夸大。手动漏做准备且没有文件时仍能回退发布，但日志不会自动精简；仓库发布规则要求先准备并检查文件。
