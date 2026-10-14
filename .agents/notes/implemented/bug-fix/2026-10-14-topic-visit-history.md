# Agent Note: 原生看帖补全服务端访问记录与历史返回刷新

Status: implemented

## Problem

用户报告手机看过的帖子不出现在浏览历史。App 已读取 /read.json 并上报 /topics/timings，但详情 JSON 未传 track_visit。Discourse TopicQuery.list_read 只列出 last_visited_at 非空的主题并按该字段排序；TopicsController 对 JSON 仅在 track_visit 且登录时调用 TopicUser.track_visit!。timings 更新已有 TopicUser 时不更新 last_visited_at，因此阅读进度不能替代访问历史。长正文尚未满足曝光规则时也不会提交 timings。已有历史页面的 ViewModel 返回时不重载列表。

## Decision

仓库 detail 提供默认 false 的 trackVisit，仅登录用户的 TopicViewModel 首次打开请求传 true；刷新、续读补窗、跳楼、邻页预取及摘要补全保持无访问标记。失败后显式重试仍属于首次打开，成功后不重复标记；每次重新进入创建新的导航上下文仍记录访问。访问不改本地 readNumbers，也不代替真实曝光 timings。

HISTORY 屏幕通过 LifecycleResumeEffect 在返回 RESUMED 时刷新。ViewModel 首次展示跳过额外加载（init 已经请求），后续返回重新读取第一页；旧条目保留到成功响应，沿用取消旧请求、代次核验及分页。其他个人列表不接入此生命周期刷新。

## Existing note audit

[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)部分取代“不用 track_visit”的决定，保留 timings、真实曝光、账号隔离和缓存规则；本篇把访问与读完分开。[续读](../../implemented/feature/2026-10-11-topic-resume-reading.md)保留最高已读及补窗规则，补窗不记访问。[个人中心](../../implemented/feature/2026-10-10-member-account-dashboard.md)及[账号列表](../../implemented/feature/2026-10-07-account-and-composer.md)部分重叠，只修复历史返回刷新，保留分页和账号边界。没有完全取代或同主题的其他 proposed/rejected。

## Alternatives considered

- 仅依赖 timings 可以维持全部详情请求无写入语义，但已有 TopicUser 的 last_visited_at 不会刷新，无法保证历史出现或更新顺序；用户实际进入主题时使用官方访问参数。
- 所有 detail 请求都附访问参数实现最少，但预取、续读补窗和引用补全会污染历史或重复访问；默认保持 false，只给首次实际打开明确传入。
- 本地历史可以立即展示并覆盖离线访问，但不解决用户要求的服务端和跨设备同步；保留服务端历史，不补写过去未保存的访问。

## Testing

TopicVisitHistoryTest 使用真实 ViewModel 和 MockWebServer，4 项中 3 项先因缺少 track_visit 失败，访客及仓库预览排除用例原本通过（`artifacts/topic-visit-history-repro.txt`）。补上参数后 4 项通过（`artifacts/topic-visit-history-fixed.txt`），验证登录访问后读取服务端模拟历史、不提前标读、刷新不重复、重新进入再次访问和明确楼层目标不变。

PersonalHistoryUiTest 使用真实屏幕、ViewModel、受控 LifecycleRegistry 与离线 HTTP 响应。旧实现返回历史后等待新条目超时，其他个人列表分页保留用例通过（`artifacts/history-return-repro-device.txt`）；修复后两项均通过，验证历史首屏仅请求一次、返回刷新服务端模拟结果、其他个人列表保留分页。

70 项关联 JVM 测试通过（`artifacts/topic-history-final-build.txt`）：访问历史 4、续读 12、楼层导航 12、实时回复 11、主题 ViewModel 9、阅读曝光计算 6、上报队列 3、账号数据 6、楼中楼与签名 7。既有详情夹具按 encodedPath 匹配路由，保留完整请求参数日志并新增首次/续读补窗/访客参数断言；未删除实际传输检查。首轮 51 项中一个延迟续读夹具仍用完整 path 分支而未触发阻塞，修正同类匹配后相关 12 项全部通过（初次日志 `artifacts/topic-history-unit-regression.txt`），不是修改产品逻辑绕过失败。

20 项真机关联测试全部通过（`artifacts/topic-history-final-device.txt`）：历史 2、阅读可见性与暂停 8、向前分页 6、实时回复生命周期 2、个人中心入口及大字号 2，没有跳过项。Debug / AndroidTest 构建与 Lint 通过，0 errors、45 既有 warnings、1 hint。没有扩大全量测试或重复无关联已通过的测试。

RMX2202 / Android 13 构建显式 versionCode=2005、versionName=0.2.3，与安装前版本相同；回读包 `artifacts/topic-history-before.apk` 核对 SHA256 签名一致（95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f），应用只 adb install -r、测试包 -r -t，再 am instrument，没有卸载或清数据。最终产物 `artifacts/kudo-topic-history.apk`。测试使用离线拦截或 MockWebServer，不修改真实账号浏览历史；真实服务器的最后访问时间及跨设备显示尚未做端到端验收。

## Consequences

登录用户打开帖子可以独立记录访问，短文与长文不再依赖读完门槛才进入历史；返回历史时能读取新列表。Discourse 访问写入是延后任务，历史查询可能短暂滞后；离线或失败的打开没有可确认的服务端记录。过去未上报的访问无法完整恢复，重新打开后才能记录。真实服务器部署与上游源码仍可能有差异，离线测试不等于真实账号端到端同步验收。
