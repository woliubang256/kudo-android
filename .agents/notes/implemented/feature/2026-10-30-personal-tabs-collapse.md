# Agent Note: 私信和话题回复仅保留标签吸顶

Status: implemented

## Problem

私信和话题/回复的标题、返回与操作按钮始终占据顶部，列表滚动后仍不能让出阅读空间。用户确认改为上滑隐藏标题行、标签留在状态栏下，反向滚动恢复标题。

## Decision

CollapsingPageScaffold 接收滚动行为和独立固定标签槽，与 MaterialPageScaffold 复用私有布局；原函数签名保持，避免向其他调用者传播 Material3 的实验类型。仅私信、话题/回复启用 enterAlwaysScrollBehavior，历史及其他页面保持原布局。状态栏安全区位于折叠区域之外，标题行按实测高度收起，标签不参与折叠；顶部和内容继续为兄弟采样层，列表通过实时顶部留白避让。

分页和数据协议保持，每页仍保留独立列表状态；点击或横滑标签保留当前折叠位置，反向滚动展开。快速回顶并行展开标题。空页和短页的 LazyColumn 也提供纵向嵌套滚动，不通过假数据或额外占位制造长列表。

## Existing note audit

[个人中心](../../implemented/feature/2026-10-10-member-account-dashboard.md)部分重叠，保留私信信箱和话题/回复分页缓存、账号隔离和刷新规则。[页面顶部材质](../../implemented/feature/2026-10-16-page-toolbar-material.md)部分扩展，只新增可选折叠契约，默认固定顶部和独立内容采样保持。[搜索折叠](../../implemented/feature/2026-10-29-search-header-collapse.md)提供按方向展开和动态留白的参考，但这里保留整行标签；[图库](../../implemented/feature/2026-10-29-local-image-library.md)已有标题折叠，仅补空目录手势，不改变其宿主。已检索 proposed、implemented、rejected；没有完全吸收项或同主题过时提案。

## Alternatives considered

- 把标题放进每页列表可以自然滚出，但要返回列表顶部才重新出现，并且会重复标题和操作状态；采用 enter-always 的独立顶部。
- 把整段页头一起移动实现最少，但会移走用户要求保留的标签和系统安全区；只折叠实测标题行。

## Testing

- 旧实现运行五项用例，私信长页、话题/回复、深色窄屏空信箱及图库空目录四项因标题仍显示而失败；图库短页已有折叠，一项通过（artifacts/personal-collapse-red-device.txt）。测试不写真实私信或帖子。
- Debug 和 AndroidTest 以显式 3002 / 0.3.1-dev 构建通过（artifacts/personal-collapse-build.txt），安装前核对签名与已安装开发版一致，以 adb install -r、adb install -r -t 覆盖保留数据。
- 首轮 43 项真机回归为 42 通过、一项旧按钮尺寸断言失败（artifacts/personal-collapse-device.txt，79.586 秒）。失败用例 expected=144px、actual=120px；用改动前同版本 APK 对照仍原样失败（artifacts/personal-collapse-button-baseline-device.txt），不改与本轮无关的按钮逻辑，也不把整批记为全绿。
- 私信长列表、话题和回复、280dp 深色 130% 字号空信箱的新增三项通过，连同个人分页/历史、草稿返回、图库操作、默认顶部采样和 WebView 普通布局覆盖原有阅读位置、缓存、写私信和草稿回调。
- 快速回顶与历史固定标题两项最终通过，耗时 9.892 秒（artifacts/personal-collapse-focused-final-device.txt）。初版快速回顶测试只反向移动 40px，全部用于展开页头，列表没有实际向上滚动，因此按已有方向规则不显示回顶按钮；改为真实反向列表手势后通过，不修改生产显隐规则（artifacts/personal-collapse-focused-device.txt）。确认回顶后标题、首项和刷新操作恢复；历史滑动后标题坐标不变。
- 人工查看 artifacts/mailboxes-tabs-pinned.png、personal-replies-tabs-pinned.png 与 library-empty-tabs-pinned.png，确认仅标签吸顶、中文可读。截图为离线夹具组件窗口，系统栏灰底不作为真实根导航的材质结论；材质独立采样由 PageMaterialUiTest 浅深色与三种材质的像素测试验证。
- 最终 lintDebug 通过，仍为 0 errors、60 warnings、1 hint（artifacts/personal-collapse-final-lint.txt）。新增两项后的测试 APK 构建通过（artifacts/personal-collapse-focused-build.txt）。本轮不同用例共 45 项，44 项分批通过，另一个是已确认旧包同样失败的按钮尺寸断言。

当前验证为 RMX2202 / Android 13，开发包版本保持 3002 / 0.3.1-dev；覆盖前检查安装包证书 SHA-256 与构建一致，firstInstallTime 仍为 2026-10-23 14:55:34。使用显式构建和 adb shell am instrument，不运行自动安装生命周期测试任务；真实账号没有发帖、私信、标读或设置写入。此改动只涉及滚动布局，沿用既有数据及网络单测，不重复无关全量 JVM 测试。

## Consequences

列表滚动后保留标签导航并让出标题空间，反向滚动可立即找回操作。代价是动态顶部留白和下拉刷新均参与嵌套滚动，需检查短列表、反向展开和快速回顶。公共 Scaffold 的默认行为不能影响其他原生页或 WebView；其他 Android 版本和 GPU 仍需额外设备覆盖。
