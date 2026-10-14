# Agent Note: 从提交整理中文Release日志

Status: implemented

## Problem

GitHub的generate-notes主要按PR整理，直接提交可能没有可用更新说明，App需要读取可理解的Release正文。

## Decision

自动发布以上一次已公开的正式Release为基线，通过 `--previous-tag` 交给脚本；基线必须是当前Tag的祖先且版本较小。失败Tag和草稿不作为已发布基线，避免后续成功版本漏掉尚未发布的App变化。没有已发布Release时显式传空基线，遍历全部历史。本地离线预览未指定该参数时仍选择上一个数字版本较小的祖先Tag。正文只保留App相关变化，提交标题按新增、修复及调整分组，去掉合并提交、空项和重复标题，并附完整比较链接。自动流程生成Markdown作为发布产物，创建或恢复草稿时用同一正文写入Release，保留验签、摘要和已发布版本不覆盖规则。手动发布前可用脚本预览并编辑生成文件，通过gh --notes-file保持真实换行。

本篇继续约束发布基线、App 提交筛选和原始候选生成；[发布前 AI 归并](2026-10-12-prepared-release-notes.md)接管最终正文的措辞，发布前由当前 AI 协作者整理并提交版本文件，CI 校验来源后读取正文。缺少版本文件时才回退本篇的原始日志；CI 不调用模型的约束仍成立。

## App commit selection

有英文类型前缀的提交仅接受feat、fix、perf，分别归入新增、修复、调整；doc、docs、build、ci、test、chore、refactor、style及其他类型不进入正文。保留项还必须实际修改App运行时源码或资源。纯脚本、CI、文档、验收笔记和测试提交即使误写成feat或fix也不进入正文。

无前缀的历史提交仅在修改app/src/main/运行时文件时保留，排除许可证和Markdown等文档文件；保留现有中文动词分类，其他App标题归入调整。使用每个提交的完整文件变化，不按路径简化Git历史，覆盖首次提交、删除及重命名；标题分类不能代替文件范围检查。所有提交被过滤时显示“本版本暂无 App 功能变更。”，没有维护分组，完整变更链接仍可查看工程提交。

## Existing note audit

[签名与Tag发布](../../implemented/process/2026-10-08-signing-and-tag-releases.md)部分重叠，本篇接管正文生成，保留签名、权限和草稿发布顺序；[更新检查](../../implemented/feature/2026-10-11-github-updates.md)读取该正文。无完全吸收和过时提案。

## Alternatives considered

- GitHub自动生成说明可复用平台PR分类，但仓库以直接中文提交为主，无法覆盖实际变化，采用确定性的提交分组。
- 调用LLM自动改写能更贴近用户语言，但CI需要额外凭据且输出不可复现，原决策保留中文提交标题和可人工预览文件。后续[发布前 AI 归并](2026-10-12-prepared-release-notes.md)通过提交整理结果解决同一功能的多次提交合并，仍不在 CI 调用模型。
- 只按feat、fix、perf白名单提取最容易维护且语义明确，但旧中文提交没有这些前缀，直接切换会丢掉历史功能；结合运行时文件范围兼容旧提交，并防止工程提交误用功能前缀。
- 只按App目录提取能保留未规范命名的功能，但app/src/test、app/src/androidTest、许可证以及无行为变化的重构也在目录内；限制运行时文件并以类型前缀排除内部维护。
- 仅选择祖先Tag可以完全离线复现，仍作为本地默认；但v0.1.6及v0.1.8有Tag而发布失败，成功版本若从这些Tag开始会漏掉App变化。自动发布查询已公开Release，并把确定的Tag交给纯Git脚本；API失败必须停止，不回退到可能遗漏变化的任意Tag。

## Verification

临时Git仓库测试首次版本、增量范围、版本排序、非祖先Tag、重复及合并提交、中文分类和无功能变化；工作流始终在发布前上传并写入该正文。

发布失败后继续发布的夹具覆盖显式已发布基线跳过两个失败Tag、首次发布空基线、拒绝非祖先/相同/更高/非法基线。工作流使用只读GITHUB_TOKEN查询所有Release，过滤草稿、预发布及当前Tag后按发布时间选择最近版本；查询失败立即停止，不静默改范围。实际shell夹具覆盖有基线、空基线和API失败三条路径，保留已有草稿上传顺序测试。

此前20项发布工具测试通过（含新增5项测试），日志artifacts/release-notes-tests.txt。从实际release.yml提取发布shell，在离线gh替身下验证新建Release、恢复草稿、已公开拒绝修改、上传失败和摘要失败；新建及重试均先写notes-file，再上传附件，最后公开。后续过滤规则补充了1项综合提交路径与类型测试，覆盖文档、CI、测试、许可证、旧版无前缀App提交、未知中文动词归入调整和无功能变化版本；当前全套21项发布工具测试通过，本地验证阶段未执行远程发布。

2026-10-11首次真实发布v0.1.5：Tag固定指向b3c072c，因没有先前稳定Tag，正文整理全部祖先提交。云端运行38074297223第二轮完整通过，GitHub Release正文与脚本本地生成文件逐字一致（忽略尾部空白），包含高频拖动修复并附Tag提交历史链接。发布后实际下载APK和摘要并验签通过，验收记录见[签名与Tag发布](2026-10-08-signing-and-tag-releases.md)；云端正文留存于artifacts/release/0.1.5/cloud/release-notes.md。仓库仍私有，App匿名更新检查留待公开后验收。

## Commit subject validation

Tag 推送的重复校验由[Actions 运行保留与主分支提交检查](2026-10-15-actions-retention-and-main-validation.md)接管：当前工作流只监听 `main` 推送和 Pull Request，不再单独监听版本 Tag；本篇以下关于 Tag 范围的记录保留为历史实现背景。

A versioned `.githooks/commit-msg` hook calls `scripts/check_commit_message.py` and accepts the supported Conventional Commit types, optional scopes, and merge subjects. The local repository enables this hook with `core.hooksPath=.githooks`; standalone tests cover valid, invalid, commented, and merge messages. Existing history remains unchanged and future commits are rejected when their subject has no recognized prefix.

The repository also provides `scripts/check_commit_history.py` for CI and release branches. It validates only the supplied revision range, so existing unprefixed history is not rewritten while every new pull request or push can be rejected before release. Clones must run `git config core.hooksPath .githooks` once to activate the local hook; the CI range check remains the shared enforcement point when a clone has not installed local hooks.

Tag推送的before全零，不代表全部祖先都是新提交。Tag校验使用当前提交父历史中最近的稳定版本Tag到当前SHA的范围；没有旧Tag时仅检查当前提交，兼容规范生效前的旧历史。普通分支更新与PR仍检查完整新增范围，不放过范围中间的无前缀提交。实际工作流shell的临时Git夹具覆盖旧历史、有效增量、增量中的非法中间提交、普通分支范围与首次Tag。

移除Tag校验可以完全消除重复检查，但会失去直接Tag发布的增量门禁；保留校验并修正范围。重写旧历史可以让全量校验通过，但会改变既有提交身份与Tag，违背只约束新提交的约定。

## Consequences

日志只呈现App功能、修复和体验变化，首次版本与增量版本使用相同过滤规则，工程变更可从完整比较链接追溯。原始候选质量仍取决于commit标题；最终正文按[发布前 AI 归并](2026-10-12-prepared-release-notes.md)审阅和整理，没有版本文件的兼容路径保留原始标题。未来拆分运行时模块时必须同步文件范围。真实云端正文已验收，但私有仓库内认证访问不能证明App的匿名访问路径；本轮生成的v0.1.5预览不代表已发布正文已被修改。
