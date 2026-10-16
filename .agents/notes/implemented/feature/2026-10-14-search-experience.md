# Agent Note: 酷安式搜索与网页搜索契约

Status: implemented

## Problem

搜索只有话题卡片，未接最近搜索、结果类型、排序和高级筛选；按 topic_id 合并会丢失同一话题的多个命中楼层。用户批准酷安式紧凑搜索栏、历史词条、三个结果标签、网页排序和右侧向下展开筛选。

## Decision

标签的固定靠左布局由[顶部操作与居中标签](2026-10-16-page-toolbar-material.md)部分取代，空间足够时等分居中；搜索类型、渐变指示器和横向分页契约保持。

搜索顶部和高级筛选下拉使用用户设置的 screenMaterial，光学参数与首页一致。全屏结果列表是独立采样层，顶部和下拉为兄弟节点；首屏用列表 contentPadding 避让实测顶部高度，滚动后内容经过玻璃后方。筛选作为顶部下方的覆盖下拉，不挤压列表视口；高度按扣除顶部后的可用空间限制，底部动作仍可见。清空确认弹窗使用包含顶部的独立全屏采样源，不能让顶部采样到自身。

搜索框使用随字号增高的椭圆容器，内部右侧保留分隔线和可点击搜索按钮；清空、键盘提交、空白禁用和输入状态保持既有语义。

三个搜索类型通过 HorizontalPager 跟手切换，只有 settled 页驱动 changeKind；预组合只展示缓存或等待态，不发请求。ViewModel按类型保存成功结果、分页游标、错误和滚动位置，返回已加载类型直接复用；新搜索、排序、筛选、清空与账号切换使旧类型缓存和位置失效。离开搜索仍保存当前页位置，详情返回延续原有恢复契约，切类型不再重置其他类型位置。

最近搜索支持账号同步清空：`clearRecentSearches` 调用网页对应的 `DELETE /u/recent-searches.json`，沿用同源会话和 CSRF 写请求，访客不写。请求成功才清空当前状态；失败保留词条和独立错误，不自动重放删除。历史读取与删除共用互斥状态和独立代际令牌，删除期间不启动历史刷新，旧读取或旧账号的迟到响应不能复活历史。

标题右侧提供垃圾桶图标，先打开居中的 LiquidModal 并使用统一的胶囊确认按钮；发送期间仅显示“正在清空…”，防止重复点击。弹窗显示时隔离底层焦点与可访问语义，禁用快速回顶；账号切换、开始搜索或清空成功都会关闭弹窗。成功后最近搜索标题与词条整块隐藏，初始空历史也不展示“暂无最近搜索”；读取中及读取失败仍提供加载和重试反馈。失败则在弹窗中显示独立错误，取消后保留历史。

三个结果类型直接复用首页 FeedTabBar，靠左紧凑排列，选中文字加粗并略放大，文字底部绘制 LogoYellow 渐变指示器；保留原生 Tab 选中语义、搜索类型切换时收起键盘和筛选的行为，不改变排序与筛选契约。

帖子沿用 GET /search.json?q=&page=，保留 posts 的顺序、摘要、作者及 post_number，关联 topics，不按主题去重。分页最多十页。类别与标签组合 /site.json 分类目录和 /tags/filter/search?q=；标签搜索及高级筛选建议共用 searchTags，不传 limit，由服务端采用站点默认数量。用户按网页 /u/search/users.json?term=&limit=20，不伪造这两类的分页。服务端 /u/recent-searches.json 仅登录请求，不落盘；账号变化取消请求并立即清空旧状态。

标签接口的 limit 是可选参数，[Tags::Search](https://github.com/discourse/discourse/blob/main/app/services/tags/search.rb)拒绝超过 SiteSetting.max_tag_search_results 的值；[站点默认配置](https://github.com/discourse/discourse/blob/main/config/site_settings.yml)为5，但站点可自行调整。初次请求固定20会被较低上限拒绝，导致类别/标签结果和标签建议失败；省略该参数避免把站点限额写死，也不依赖筛选元数据先成功加载。

SearchData 独立承载三类结果、排序枚举与能力元数据，保留既有 repository.search 的兼容契约。排序枚举使用网页相关性、latest、likes、views、latest_topic、read；read 仅登录可用。插件能力按 HTML siteSettings/currentUser 判断，不以未知能力猜测权限。

SearchQuery 以引号感知的 token 切分实现面板条件替换；排序只替换 order:，筛选只替换已知面板操作符，保留引号中的字面文本、未知语法及 in:replies 等面板未管理的条件。标签 AND 使用 +、OR 使用逗号；数字必须非负且最小值不超过最大值，日期按 LocalDate 校验，用户名/群组拒绝空格和操作符注入。

完整被引号包围的 category: 文本是字面词语；category:"名称 含空格" 则是分类操作符，换分类必须替换它，不能保留为第二个互相冲突的条件。with:images 大小写不影响语义。两项边界先测试失败（artifacts/search-quoted-query-repro.txt）再修复。

网页的 l/r 排序缩写映射为 latest/read，改变排序时移除旧缩写而不触及引号字面文本。多个单标签条件保持交集，避免面板重新应用时扩大结果；两项先失败（artifacts/search-alias-repro.txt）再修复。

多个标签子句含逗号时可能表达“(a 或 b) 且 c”，不能压平成单一列表。未编辑标签条件时沿用原始子句，仅改变其他筛选字段；修改标签集合或所有标签选项才改为面板的新条件。混合标签用例先失败（artifacts/search-mixed-tags-repro.txt）再修复。

SearchViewModel 从 FeedViewModel 分离。账号、搜索与标签建议各有代际令牌，取消后迟到的响应不能覆盖新状态。历史、元数据、标签错误独立于主结果。分页成功才推进 page，失败保留命中和下一页重试位置。换号同步取消四类任务并重置状态，forAccount 在 Compose effect 执行前也屏蔽旧账号数据。类别/用户请求仅提取词语，不携带帖子排序和筛选；帖子搜索成功后重新读取服务端历史，不伪造本地记录。

SearchFiltersPanel 使用 Foundation 输入、自绘选项和黄色选中态；分类可搜索、标签建议来自服务器，范围/话题状态/日期方向在面板内展开，提供固定底部重置与应用。个人范围仅登录显示，已解决、指定给和专家回应由站点/账号能力决定；读取失败保留基本字段并可重试。

SearchScreen 合并返回键、无描边搜索框和搜索动作于同一顶栏；三类标签、横向排序和固定右侧筛选图标保持紧凑。筛选向下展开，占剩余高度最多 72%，内部滚动、底部按钮固定，并接收 IME 避让。信息流沿用 TopicCard，传统列表补充命中摘要，两者保留真实楼层入口；用户显示头像/昵称/用户名，分类与标签进入原生列表。普通搜索与用户限定搜索均连接账号变更，继续注册快速回顶，不显示假热门数据。

搜索提交、历史词条与控件切换收起输入焦点；短提示避免大字号将顶栏撑成两行。分类/标签结果仅在主文字区显示名称，前导使用图标，避免重复朗读；先用真机断言复现两个分类名称节点（artifacts/search-entity-repro-device.txt）。系统返回先收起筛选，再允许退出搜索。

## Related decisions

顶部标签复用[首页标签与吸顶](2026-10-08-home-tabs-and-collapse.md)的 FeedTabBar 样式；只复用标签组件，不把首页滚动折叠行为引入搜索页。

检索 proposed、implemented、rejected 搜索相关笔记，无专属搜索决策。与[数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)、[主题展示](../../implemented/feature/2026-10-09-topic-presentation.md)、[酷安表单](../../implemented/feature/2026-10-10-coolapk-forms.md)、[快速回顶](../../implemented/feature/2026-10-11-quick-scroll-to-top.md)部分重叠：沿用同源凭据隔离、标签 slug、全局列表样式、自绘控件、滚动注册；这些旧篇仍各有独立职责。其余命中是页面布局、导航和过去测试记录，非本方案归属，无完全吸收或过时提案。

## Alternatives considered

- 各类型共用列表并在切换时重新搜索，内存和状态最少，但横滑回来会丢位置且增加请求；使用按类型缓存和settled页请求，分页未完成离开也保留已成功页。

- 只清空内存历史实现最简单且无需写接口，但重新进入或在网页打开仍会恢复；采用与网页相同的账号删除接口，成功后同步更新界面。
- 先隐藏历史再异步删除能更快响应，但失败会误报成功并在下一次读取时突然恢复；保留历史直到服务器确认，弹窗展示处理中与错误。
- /search/query.json 可以一次返回用户、分类和标签，实现最少；但顶部搜索每类只有五项，网页完整页面实际使用专用用户和标签接口，因此不用它充当完整结果页。
- 客户端本地排序最容易复用现有列表；只能排列当前页，无法实现网页全站排序，故使用服务端 order:。
- 底部筛选面板可复用已有 CommunitySheet；用户明确要求图标点击下拉，因此采用排序条下方的展开面板，限制高度并支持内部滚动。
- 将标签 limit 改成5能匹配 Discourse 默认上限且只改一处，但站点允许把上限设为1，仍会失败；采用可选参数省略时的服务器默认。先读取 max_tag_search_results 再发送可以显式匹配配置，但需要额外元数据依赖，完整搜索原本不依赖高级筛选加载，因此不增加这项前置请求。

## Testing

清空接口与状态定向 18 项 JVM 测试通过（`artifacts/search-history-state-tests.txt`），覆盖 CSRF DELETE、访客不写、成功前保留历史、重复提交及刷新隔离、失败保留词条后手动重试、取消在途读取与换号隔离；所有请求使用 MockWebServer，不清空真实账号历史。

最近搜索清空与首页同款 Tab 的 SearchUiTest 11 项真机通过（`artifacts/search-history-ui-device.txt`），新增液态确认、取消不发 DELETE、成功后整个板块隐藏且再次读取仍为空、错误弹窗保留历史、初始空列表隐藏；既有三类原生跳转、排序、筛选、深色大字号和短窗口继续通过。确认按钮统一为胶囊后两项弹窗用例复跑通过（`artifacts/search-history-dialog-final-device.txt`）。Debug 和测试 APK 构建通过，Lint 0 errors / 45 既有 warnings / 1 hint。

同机浅色与深色130%字号确认弹窗、清空后页面和三类结果截图已查看，保存于 `artifacts/search-history-ui/`；Tab 与垃圾桶点击后的灰面是瞬时波纹。使用2005/0.2.3与既有Debug签名覆盖安装，未卸载或清数据。普通搜索与用户限定搜索均接入清空回调，真机离线 HTTP 夹具不修改真实账号的历史。

标签限额的两项复现先失败，均为 ApiFailure: limit 无效（artifacts/search-limit-repro-tests.txt）：类别/标签组合搜索夹具模拟上限5，直接标签建议夹具模拟上限1，验证固定20会被400拒绝。修复后 SearchDataTest 七项与 SearchViewModelTest 六项共13项通过（artifacts/search-limit-fixed-build.txt），请求保留中文/空格查询及标签 slug，且不发送 limit。RMX2202 / Android13 的三类原生跳转与排序/下拉筛选两项真机回归通过（artifacts/search-limit-fixed-device.txt）；显式2005 / 0.2.3同签名覆盖安装，保留数据。本次为离线服务契约复现，未以真实登录会话请求标签接口。

SearchDataTest 六项通过（artifacts/search-data-tests.txt），覆盖命中顺序、多个楼层、真实作者、标签 slug、分页上限、访客零历史请求、严格历史字段、专用用户/标签接口和权限能力。测试不向真实社区写入内容，不卸载或清数据。

SearchQueryTest 九项通过，覆盖所有面板 token、引号字面文本、带引号分类参数、未知操作符、范围与日期校验、排序缩写、重复与混合标签语义；相关失败复现日志与修复日志保留于 artifacts/。

SearchViewModelTest 六项通过，覆盖访客无历史请求、独立历史错误、失败下一页原位重试、用户搜索剥离帖子条件、连续搜索迟到响应和退出时在途历史隔离。最终与数据六项、查询九项、主题元数据六项共二十七项 JVM 测试通过；Debug 构建与 Lint 通过，0 errors / 45 既有 warnings / 1 hint（artifacts/search-delivery-build.txt）。最终 APK 为 artifacts/kudo-search.apk，已覆盖安装到真机。

SearchUiTest 八项与搜索入口/主题样式/楼层定位五项回归共十三项真机通过（artifacts/search-verified-device.txt），覆盖历史登录隔离、三类原生跳转、真实命中楼层、排序与高级条件、深色130%字号、短窗口校验与固定底部动作、返回先收起筛选。初次重置断言把提示文字也当成输入，日志 EditableText 已为空；改为直接检查 EditableText，未改产品重置逻辑。

截图 search-post-results.png、search-category-tag-results.png、search-user-results.png、search-filters-dark-large.png 与 search-filters-short-window.png 已查看；根节点截图不包含独立系统键盘窗口。类别/标签截图中的灰色是点击波纹，非静止选中背景。

## Follow-up: restore result position after topic navigation

The search ViewModel keeps one result-list index and pixel offset per kind, separately from search results. `SearchScreen` saves the current position before switching kind and when its composition leaves, then restores the target kind or recreated route from that pair. A new search, account change, or clear resets all pairs, while switching kind and loading more preserve positions. This preserves the exact search position when opening a topic and returning through the navigation back stack.

`SearchUiTest.returningFromTopicRestoresTheSearchResultPosition` failed before this follow-up because the twentieth result disappeared after the simulated detail return; it passes with the ViewModel-backed position.

## Consequences

横滑与缓存调整的12项SearchViewModelTest通过；先复现切回已加载类型会重搜，以及第三页在途切走会退回旧的一页缓存，随后修复至通过（artifacts/search-tabs-cache-repro.txt、search-tabs-pending-page-repro.txt、content-tabs-final-checks.txt）。SearchUiTest的15项独立用例通过，分布在content-pagers-tests.txt和content-tabs-final-tests.txt，覆盖点击/横滑同步、只请求settled类型、复用成功结果、跨两类型恢复第20项、详情返回及280dp/130%字号的三种材质筛选。缓存增加有限内存，主动搜索或筛选仍刷新所有类型，避免显示与新条件不符的结果。

2026-10-16 搜索材质相关13项真机回归通过（artifacts/top-material-tests.txt，另含类别和标签2项）。覆盖椭圆内提交与空白禁用、浅深色三种材质、筛选覆盖不改变列表视口、短窗口动作可见、清空历史、详情返回位置、键盘与服务器筛选；已查看浅色大字截图。版本2011/0.2.6-dev且签名与已安装包一致，覆盖安装保留账号数据。

历史依赖服务端搜索日志，可能延后出现；站点关闭日志时独立显示读取错误，不阻塞搜索。插件能力读取可能被验证页阻挡，提供重试。用户接口按网页最多二十项，标签请求有限额；这些结果不声称无限分页。真机仅使用覆盖安装，版本为2005/0.2.3，签名与已安装包匹配，保留账号及数据。线上接口已参考官方源码核对，真机使用离线夹具，尚未完成真实登录账号的跨设备最近搜索同步验收。

清空操作需要服务器确认；网络失败保留本地显示供用户核对，不假报完成。接口与网页同步，可能影响其他设备上的账号历史；本轮测试只清空离线夹具，不删除用户真实搜索记录。
