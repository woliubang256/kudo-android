# Agent Note: 类别与标签目录跟随网页侧栏配置

Status: implemented

## Problem

分类页把 /site.json 的完整类别目录直接平铺，缺少标签入口，也没有应用网页侧栏的个人显示项和站点排序。完整元数据目录同时服务帖子标题和搜索，不能因侧栏筛选而缩减。

## Decision

目录独立读取 /latest 的 data-preloaded 快照（兼容属性和 application/json 脚本），从同一次响应解析 site、siteSettings、currentUser。核对当前账号，拒绝把另一账号或匿名快照发布到当前目录。网页类别使用 sidebar_category_ids 筛选完整目录，再遵循 fixed_category_positions 和父子层级排序；匿名使用 default_navigation_menu_categories。无个人选择时按网页回退顶层类别前五项。个人 sidebar_tags 使用网页的名称比较规则；默认/热门标签保留服务器集合顺序。display_sidebar_tags 和 tagging_enabled 继续控制可见内容，不用热门标签冒充隐藏的个人列表。

类别和标签使用独立 Tab 与滚动状态；类别保留图标介绍卡片，标签使用可滚动宫格，手机通常三列，窄屏/大字号减少列数，宽屏增加。全部类别来自同一完整目录；全部标签按需读取 /tags.json，保留服务器分组和数组顺序。私信专用标签进入网页对应的私信标签地址，不能误请求公开标签列表。

目录 ViewModel 按账号代次隔离在途结果，失败保留同账号成功数据；切 Tab 不请求网络，下拉刷新重新读取，恢复前台仅在缓存过期时刷新。返回详情保留 Tab、目录模式及位置。功能按数据协议、状态管理、界面接线分批提交。

## Related decisions

已检索目录、侧栏、标签和分类配置。[分类图标](../../implemented/feature/2026-10-08-category-web-icons.md)部分重叠，保留图标、卡片和安全区，新增 Tab 取代“无顶部区域”；[主题展示](../../implemented/feature/2026-10-09-topic-presentation.md)与[公开主页数据](../../implemented/architecture/2026-10-10-public-profile-data.md)部分重叠，完整分类目录不受侧栏筛选影响；[分类详情](../../implemented/feature/2026-10-10-category-detail-header.md)仅复用元数据，不改列表头。没有完全吸收或过时提案。

## Alternatives considered

- 直接读取 /site.json 的类别与 top_tags：请求和解析最少，但前者是完整权限目录，后者是热门标签，不能代表网页侧栏的个人选择。
- 把侧栏选择写入 Member 并复用 AccountViewModel.categories：账号刷新时可顺手获得配置，但会把筛选目录与其他功能所需的完整分类元数据混为一体；目录使用独立快照和状态。
- 标签逐行展示可完整容纳长名称，但用户明确要求宫格；宫格单元允许两行名称，随宽度与字号调整列数。

## Testing

CommunityDirectoryTest ?6?????????????????????????????????????????????????????3? CategoryMetadataTest ??????? artifacts/community-directory-data-tests.txt?

## Consequences

网页未来新增主题变换器或自定义排序协议时需同步适配；未收录图标继续使用现有通用图标。/latest 读取体积比 JSON 元数据大，限制为目录进入或过期刷新，切 Tab 不重复读取。
