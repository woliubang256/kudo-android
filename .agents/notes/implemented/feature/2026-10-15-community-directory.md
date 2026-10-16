# Agent Note: 类别与标签目录跟随网页侧栏配置

Status: implemented

## Problem

分类页把 /site.json 的完整类别目录直接平铺，缺少标签入口，也没有应用网页侧栏的个人显示项和站点排序。完整元数据目录同时服务帖子标题和搜索，不能因侧栏筛选而缩减。

## Decision

两项标签的固定靠左布局由[顶部操作与居中标签](2026-10-16-page-toolbar-material.md)部分取代，空间足够时等分居中；目录顺序、分页状态和材质采样边界保持。

类别/标签内容使用 HorizontalPager，点击与横滑共享页码；顶部材质采样仍为独立兄弟层。侧栏/完整目录四份列表位置和两个展开模式分别保存，切页不重置。只有 settled 页允许下拉刷新、注册回顶目标和触发全部标签请求，预组合不请求。首页内嵌目录保留子Pager的边界手势，子页内部横滑优先，目录边界可交还首页Pager。

目录独立读取 /latest 的 data-preloaded 快照（兼容属性和 application/json 脚本），从同一次响应解析 site、siteSettings、currentUser。核对当前账号，拒绝把另一账号或匿名快照发布到当前目录。网页类别先遵循 fixed_category_positions 和父子层级排序完整目录，再按 sidebar_category_ids 筛选成员；个人 ID 数组不是显示顺序。匿名使用 default_navigation_menu_categories。无个人选择时按网页回退顶层类别前五项。个人 sidebar_tags 使用网页的名称比较规则；默认/热门标签保留服务器集合顺序。display_sidebar_tags 和 tagging_enabled 继续控制可见内容，不用热门标签冒充隐藏的个人列表。

数据协议保留完整类别目录与侧栏筛选结果。全部标签按需读取 /tags.json，保留服务器分组和数组顺序；私信标签保留 pm_only，不混同公开标签。完整元数据目录继续由 CommunityRepository.categories() 服务其他功能，独立目录接口不发布筛选结果到 categoryCatalog。

CommunityDirectoryViewModel 按账号代次取消并隔离目录与全部标签请求，UI 通过 forAccount 在切换帧立即遮蔽旧账号数据。成功结果缓存，失败保留同账号成功数据，全部标签失败不影响类别和侧栏。切 Tab 读取缓存，进入或前台恢复时只在成功快照超过60秒后刷新，用户主动刷新可越过时限，同类在途请求合并。

底栏分类和首页类别入口共享目录 ViewModel。CommunityDirectoryScreen 顶部复用首页 FeedTabBar 的紧凑标签与渐变指示器，并在 Backdrop 层中按用户选择的 GlassMode 呈现液态、模糊或半透明材质；类别保留原生卡片，标签用 LazyVerticalGrid 自适应宫格；最小列宽随字号放大，同时保证280dp窄窗口仍可显示两列。普通手机三列，宽窗口增加列数，标签名称最多两行。所有类别和所有标签作为独立模式，分别保存侧栏与完整目录的四份滚动状态；Tab、模式随导航保存，账号切换时重置。返回键先退出完整目录模式，下拉刷新复用相应请求。私信专用标签进入账号的私信标签网页，普通标签打开现有原生标签流。

## Related decisions

已检索目录、侧栏、标签和分类配置。[分类图标](../../implemented/feature/2026-10-08-category-web-icons.md)部分重叠，保留图标、卡片和安全区，新增 Tab 取代“无顶部区域”；[主题展示](../../implemented/feature/2026-10-09-topic-presentation.md)与[公开主页数据](../../implemented/architecture/2026-10-10-public-profile-data.md)部分重叠，完整分类目录不受侧栏筛选影响；[分类详情](../../implemented/feature/2026-10-10-category-detail-header.md)仅复用元数据，不改列表头。没有完全吸收或过时提案。

## Alternatives considered

- 在原内容区只识别一次横滑后修改Tab，可保留最少布局代码，但内容不能跟手移动、嵌套首页也没有Pager边界协作；使用独立子Pager，四份滚动状态继续由目录持有。

- 直接读取 /site.json 的类别与 top_tags：请求和解析最少，但前者是完整权限目录，后者是热门标签，不能代表网页侧栏的个人选择。
- 把侧栏选择写入 Member 并复用 AccountViewModel.categories：账号刷新时可顺手获得配置，但会把筛选目录与其他功能所需的完整分类元数据混为一体；目录使用独立快照和状态。
- 标签逐行展示可完整容纳长名称，但用户明确要求宫格；宫格单元允许两行名称，随宽度与字号调整列数。

## Testing

2026-10-16目录9项真机回归全部通过（artifacts/content-pagers-tests.txt，另含搜索14项）。新增左右滑动恢复完整类别第16项、侧栏切页不预取全部标签，以及嵌套首页的子页切换与边界交回手势。目录数据与排序不变，没有重复测试无关解析逻辑。

CommunityDirectoryTest 的6项用例验证个人显示项与站点顺序、两种预载格式、匿名默认、隐藏标签、账号快照拒绝、标签分组和缺失数据错误。与3项 CategoryMetadataTest 一起通过，日志 artifacts/community-directory-data-tests.txt。

CommunityDirectoryViewModelTest 验证在途合并、缓存过期、主动刷新、迟到旧账号结果拒绝、退出清空、同账号刷新失败保留和全部标签独立重试。

CommunityDirectoryUiTest 覆盖侧栏成员筛选、标签三列与服务器顺序、分组与独立失败重试、侧栏和完整目录进入详情后的 Tab/模式/位置恢复，以及深色280dp/130%字号和宽窗口增加列数；新增用例验证目录顶部的 Backdrop 容器、Tab 选择和深色液态模式。CategoriesUiTest 保留卡片、图标、状态栏安全区与错误入口的回归。首轮界面测试因顶部 Tab 同样可滚动，通用 hasScrollAction 定位器匹配两个节点；列表添加唯一 testTag 后，返回位置用例通过。此前 HomePagerUiTest、TagFeedUiTest、CategoryFeedUiTest 共10项通过，后续只重跑受改动影响的目录与类别测试。

最终12项定向JVM测试及合计17项相关真机用例通过。日志 artifacts/community-directory-state-tests.txt、community-directory-ui-final-device.txt（4项目录+2项旧类别）、community-directory-final-device.txt（新增完整目录返回场景后5项目录）及首轮 community-directory-ui-device.txt（含10项列表相关回归）。最终 assembleDebug、assembleDebugAndroidTest、lintDebug 成功，Lint为0 errors / 46 warnings，目录源码无警告；日志 community-directory-final-build.txt。已目视浅色三列、深色窄屏大字两列与宽窗口五列截图 directory-tags-light.png / directory-tags-dark-narrow-large.png / directory-tags-wide-large.png，点击后的单元灰底为涟漪状态。签名与真机已安装开发版一致，使用 adb install -r 覆盖安装，保持0.2.5 / 2006及账号数据，测试包单独 install -r -t 后 am instrument，不使用自动卸载任务。

线上只读 CommunityDirectoryLiveTest 默认跳过，显式 directoryLiveReadOnlyProbe=true 才读取当前会话和快照。RMX2202 / Android13 的探针及真实分类页均触发 Cloudflare 网页验证；应用内正常验证页要求人工验证，未自动绕过或声称线上目录校验成功。探针失败日志为 artifacts/community-directory-live-device.txt，真实页面记录为 directory-real-categories.json / directory-real-verification.json；成功探针只保存名称证据，不保存账号 HTML。

## Consequences

类别与标签现在能反映网页侧栏的个人显示项及服务器顺序，完整元数据不受侧栏筛选影响。网页未来新增主题变换器或自定义排序协议时需同步适配；未收录图标继续使用现有通用图标。/latest 读取体积比 JSON 元数据大，限制为目录进入或过期刷新，切 Tab 不重复读取。Cloudflare 验证未通过时展示现有验证入口，真实账号的最终目录核对仍需在设备上完成验证。
