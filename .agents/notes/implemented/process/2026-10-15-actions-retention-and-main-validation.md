# Agent Note: Actions 运行保留与主分支提交检查

Status: implemented

## Problem

提交信息工作流同时监听所有分支和版本 Tag。版本 Tag 必然指向已经进入 `main` 的提交，因此同一批提交会被再次检查。Actions 运行、JUnit 报告和发布中转产物也会持续积累，超过仓库保留上限后会影响后续构建和排障。

## Decision

提交信息工作流只在 `main` 推送和 Pull Request 上运行，移除版本 Tag 触发。`main` 推送使用 GitHub 提供的 `before..sha` 范围，Pull Request 继续检查目标分支到当前提交的范围；版本 Tag 由发布工作流负责版本、日志、测试和签名门禁，不再重复调用提交信息检查。同一分支使用并发组，新的检查开始时取消旧的进行中运行，避免连续推送重复消耗配额。

新增 `cleanup.yml`，每周运行一次，也支持手动触发。默认删除创建时间超过 30 天的已完成 workflow runs 和未过期 Actions artifacts；手动输入保留天数必须是至少 7 天的整数。清理只使用 `actions: write` 权限删除 Actions 数据，GitHub Release 的正式 APK 和校验文件不属于这些中转产物，继续保留。清理任务使用并发组避免计划任务与手动任务同时删除同一对象。

## Alternatives considered

- 保留 Tag 提交检查可以在发布入口再次阻挡非法提交，但 Tag 已经由 `main` 提交产生，会重复消耗运行次数；保留 Pull Request 和 `main` 检查并让发布工作流负责 Tag 门禁。
- 只缩短上传产物的 `retention-days` 能减少 APK/JUnit 中转文件，但不会清理历史 workflow runs 和日志；同时删除过期运行和未过期 artifacts。
- 每次发布后立即删除旧运行可以更快释放空间，但会失去近期失败构建的排障记录；按周执行并保留 30 天，手动入口允许在容量紧张时调整。
- 删除 GitHub Release 附件会进一步节省空间，但会破坏 App 更新和用户下载；清理范围限定在 Actions 数据。

## Consequences

正常的版本 Tag 不再产生重复的提交检查运行，连续的同分支推送也只保留最新的进行中检查，发布仍保留完整的版本和安全门禁。最近 30 天的失败运行可用于排障，旧日志和中转文件会自动释放空间；需要更长审计周期时可在清理前手动提高保留天数。清理工作流需要 `actions: write`，若 GitHub API 删除失败，任务会失败并保留错误日志，不静默报告成功。

## Verification

提交信息测试验证工作流只声明 `main` 推送和 Pull Request、没有 Tag 触发，并用临时 Git 历史检查主分支增量范围。清理脚本通过 `bash -n` 语法检查；在 GitHub Actions 中以默认 30 天计划运行或手动输入至少 7 天后，核对已完成旧运行、Actions artifacts 和正式 Release 附件的保留边界。

