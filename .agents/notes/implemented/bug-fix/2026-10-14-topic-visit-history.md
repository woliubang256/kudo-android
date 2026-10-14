# Agent Note: 原生看帖补全服务端访问记录与历史返回刷新

Status: implemented

## Problem

用户报告手机看过的帖子不出现在浏览历史。App 已读取 /read.json 并上报 /topics/timings，但详情 JSON 未传 track_visit。Discourse TopicQuery.list_read 只列出 last_visited_at 非空的主题并按该字段排序；TopicsController 对 JSON 仅在 track_visit 且登录时调用 TopicUser.track_visit!。timings 更新已有 TopicUser 时不更新 last_visited_at，因此阅读进度不能替代访问历史。长正文尚未满足曝光规则时也不会提交 timings。已有历史页面的 ViewModel 返回时不重载列表。

## Decision

仓库 detail 提供默认 false 的 trackVisit，仅登录用户的 TopicViewModel 首次打开请求传 true；刷新、续读补窗、跳楼、邻页预取及摘要补全保持无访问标记。失败后显式重试仍属于首次打开，成功后不重复标记；每次重新进入创建新的导航上下文仍记录访问。访问不改本地 readNumbers，也不代替真实曝光 timings。

## Existing note audit

[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)部分取代“不用 track_visit”的决定，保留 timings、真实曝光、账号隔离和缓存规则；本篇把访问与读完分开。[续读](../../implemented/feature/2026-10-11-topic-resume-reading.md)保留最高已读及补窗规则，补窗不记访问。[个人中心](../../implemented/feature/2026-10-10-member-account-dashboard.md)及[账号列表](../../implemented/feature/2026-10-07-account-and-composer.md)部分重叠，只修复历史返回刷新，保留分页和账号边界。没有完全取代或同主题的其他 proposed/rejected。

## Alternatives considered

- 仅依赖 timings 可以维持全部详情请求无写入语义，但已有 TopicUser 的 last_visited_at 不会刷新，无法保证历史出现或更新顺序；用户实际进入主题时使用官方访问参数。
- 所有 detail 请求都附访问参数实现最少，但预取、续读补窗和引用补全会污染历史或重复访问；默认保持 false，只给首次实际打开明确传入。
- 本地历史可以立即展示并覆盖离线访问，但不解决用户要求的服务端和跨设备同步；保留服务端历史，不补写过去未保存的访问。

## Testing

TopicVisitHistoryTest 使用真实 ViewModel 和 MockWebServer，4 项中 3 项先因缺少 track_visit 失败，访客及仓库预览排除用例原本通过（`artifacts/topic-visit-history-repro.txt`）。补上参数后 4 项通过（`artifacts/topic-visit-history-fixed.txt`），验证登录访问后读取服务端模拟历史、不提前标读、刷新不重复、重新进入再次访问和明确楼层目标不变。

## Consequences

登录用户打开帖子可以独立记录访问，短文与长文不再依赖读完门槛才进入历史。历史返回刷新尚未接入，另一个小单元处理。Discourse 访问写入是延后任务，历史查询可能短暂滞后；离线或失败的打开没有可确认的服务端记录。过去未上报的访问无法完整恢复，重新打开后才能记录。真实服务器部署与上游源码仍可能有差异，离线测试不等于真实账号端到端同步验收。
