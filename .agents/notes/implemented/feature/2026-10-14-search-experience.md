# Agent Note: 酷安式搜索与网页搜索契约

Status: implemented

## Problem

搜索只有话题卡片，未接最近搜索、结果类型、排序和高级筛选；按 topic_id 合并会丢失同一话题的多个命中楼层。用户批准酷安式紧凑搜索栏、历史词条、三个结果标签、网页排序和右侧向下展开筛选。

## Decision

帖子沿用 GET /search.json?q=&page=，保留 posts 的顺序、摘要、作者及 post_number，关联 topics，不按主题去重。分页最多十页。类别与标签按网页组合 /site.json 分类目录和 /tags/filter/search?q=&limit=；用户按网页 /u/search/users.json?term=&limit=20，不伪造这两类的分页。服务端 /u/recent-searches.json 仅登录请求，不落盘；账号变化取消请求并立即清空旧状态。

SearchData 独立承载三类结果、排序枚举与能力元数据，保留既有 repository.search 的兼容契约。排序枚举使用网页相关性、latest、likes、views、latest_topic、read；read 仅登录可用。插件能力按 HTML siteSettings/currentUser 判断，不以未知能力猜测权限。

SearchQuery 以引号感知的 token 切分实现面板条件替换；排序只替换 order:，筛选只替换已知面板操作符，保留引号中的字面文本、未知语法及 in:replies 等面板未管理的条件。标签 AND 使用 +、OR 使用逗号；数字必须非负且最小值不超过最大值，日期按 LocalDate 校验，用户名/群组拒绝空格和操作符注入。

## Related decisions

检索 proposed、implemented、rejected 搜索相关笔记，无专属搜索决策。与[数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)、[主题展示](../../implemented/feature/2026-10-09-topic-presentation.md)、[酷安表单](../../implemented/feature/2026-10-10-coolapk-forms.md)、[快速回顶](../../implemented/feature/2026-10-11-quick-scroll-to-top.md)部分重叠：沿用同源凭据隔离、标签 slug、全局列表样式、自绘控件、滚动注册；这些旧篇仍各有独立职责。其余命中是页面布局、导航和过去测试记录，非本方案归属，无完全吸收或过时提案。

## Alternatives considered

- /search/query.json 可以一次返回用户、分类和标签，实现最少；但顶部搜索每类只有五项，网页完整页面实际使用专用用户和标签接口，因此不用它充当完整结果页。
- 客户端本地排序最容易复用现有列表；只能排列当前页，无法实现网页全站排序，故使用服务端 order:。
- 底部筛选面板可复用已有 CommunitySheet；用户明确要求图标点击下拉，因此采用排序条下方的展开面板，限制高度并支持内部滚动。

## Testing

SearchDataTest 六项通过（artifacts/search-data-tests.txt），覆盖命中顺序、多个楼层、真实作者、标签 slug、分页上限、访客零历史请求、严格历史字段、专用用户/标签接口和权限能力。界面与状态验证随对应实现补充，测试不向真实社区写入内容，不卸载或清数据。

## Consequences

历史依赖服务端搜索日志，可能延后出现；站点关闭日志时需独立显示读取错误，不阻塞搜索。插件能力读取可能被验证页阻挡，需可重试。用户接口按网页最多二十项，标签请求有限额；这些结果不声称无限分页。真机验收需保留安装版本及签名。
