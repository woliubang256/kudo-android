# Agent Note: 全局液态玻璃快速回顶

Status: implemented

## Problem

长列表和话题详情缺少便捷回到顶部的入口。用户要求参考酷安，在底部居中显示小圆形液态玻璃上箭头，滚动后出现，设置默认打开；同时精简回复计数，不足最低字数时显示当前/最低字数，达到后仅显示当前字数。

## Decision

显示时机由[回顶方向显隐](2026-10-29-scroll-top-direction.md)部分接管：向下隐藏，向上且达到原有距离门槛才显示；以下距离计算、样式、位置和回顶动作继续成立。

显示设置持久化showScrollToTop，缺失旧配置默认true。公共QuickScrollToTopHost采样真实页面背景，复用现有LiquidButton和材质档位。各主列表通过Modifier注册自己的滚动状态、可见视口、启用条件和回顶动作，不替换原生LazyColumn/Grid，不改变原有滚动语义、布局和分页。只选择当前已恢复生命周期且可见面积最大的主视口，隐藏的Pager页不响应；目标离开组合即注销。

按钮位于当前视口底部中央，给系统导航、首页导航和话题操作栏留出距离，键盘显示时隐藏。页面在顶部时隐藏；距离门槛为离顶部约一个当前滚动视口，满足方向条件后淡入，返回阈值内再次隐藏，单独收起页头不触发。点击平滑回顶并展开页头。操作期间防重复，离开页面取消旧操作。菜单/面板覆盖时禁用底层目标。话题远楼层复用跳转窗口读取1楼，不补齐中间楼层；读取成功后定位标题，失败保留原内容并允许重试。

普通ScrollState与WebView按真实像素偏移和各自视口高度比较。Lazy列表/网格记录开头已测量条目/行的高度与间距，避免长首帖离屏后被短回复的平均高度误判；只缓存最多首视口像素数对应的行数，随宽度变化清空，条目换key时丢弃失效后缀。首次恢复或直接跳到尚未测量的开头之外时，对未知前缀用当前可见行均高估算，因此阈值为约一屏。折叠页头仍随回顶展开，但其偏移不再绕过距离门槛。远楼层窗口缺少前部时仍满足距离条件，入口显隐继续遵守方向规则，聊天保持最新消息位置隐藏、回到最早已加载消息的语义。

回顶样式按同机酷安实测：480dpi设备的可见圆为[492,2067][588,2163]，直径96px即32dp；图形为顶部横线加向上折角，自绘18dp矢量替代普通KeyboardArrowUp。来源截图artifacts/coolapk-quick-top-reference.png及局部icon-reference.png，布局coolapk-quick-top-layout.xml；液态材质和默认开关保留。可见32dp圆位于透明48dp点击层中央，按压缩放反馈只作用于圆面，视觉尺寸与触摸范围分开；定位补偿8dp触摸外扩，保证圆面到栏位仍为12dp。

同机酷安按钮底部2163px，详情操作栏顶部2196px，间距33px约11dp，取12dp适配密度取整。首页tabbar高度56dp、底部外距20dp，回顶避让为系统导航+88dp；详情操作栏高度48dp、底部外距20dp，避让由+96dp改为+80dp，两处均保留12dp可见间隙。

showScrollToTop在设置中默认打开，开关即时生效；回复底部只保留计数，不足服务端最低字数时显示“当前 / 最低 字”，达标后显示“当前 字”，校验与上传/发布状态不变。

## Related decisions

检索回顶、scrollToTop、液态、折叠、分页。[官方玻璃组件](../../implemented/architecture/2026-10-07-backdrop-demo-navigation.md)部分重叠，复用按钮及兼容边界；[主页连续滚动](../../implemented/bug-fix/2026-10-10-profile-header-scroll.md)和[首页顶部](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)保留原手势消费顺序；[楼层窗口](../../implemented/bug-fix/2026-10-11-topic-jump-window.md)保留有限窗口策略；[回复长度](../../implemented/bug-fix/2026-10-10-reply-length.md)只精简展示，校验与服务端阈值不变。均部分重叠，无完全取代或过时提案。

## Alternatives considered

- 固定滚动条数容易实现且无需测量，但长正文、紧凑列表及多列网格每条占用高度不同，采用视口高度与实测行高判断。
- 只用当前可见行均高估算省去缓存，但长首帖离屏后会因后续短回复低估距离，保留有界的开头测量，未知前缀才使用估算。

- 每页复制按钮和玻璃采样：接入直观且可局部控制；但材质、避让和生命周期逻辑分散，选公共Host，页面只声明实际滚动目标。
- 全面替换LazyColumn为带按钮的容器：能少写接入代码；但Modifier父级布局数据、测试语义、阅读曝光及嵌套滚动可能改变，保留原滚动组件并注册目标。
- 远楼层先逐页补齐再回顶：能形成完整连续列表；但长话题等待不可控，沿用既有按目标窗口读取策略。

## Testing

延后触发前，5项离线真机用例均在旧版因短距离已有按钮而失败（artifacts/quick-top-threshold-repro-ui.txt）。修改后首轮31项通过（quick-top-threshold-fixed-ui.txt），补查26项通过（quick-top-threshold-boundaries-ui.txt），扣除重复的视口高度用例，共56项不同UI回归通过。覆盖短距离隐藏、超过当前视口显示、返回阈值内隐藏、视口高度变化、长首帖与短回复、网格按行判断、普通滚动、WebView实际滚动及回顶、恢复远处位置、反向聊天最早消息、远楼层失败重试、主页/个人中心页头、分页与遮挡条件。Debug/AndroidTest和Lint通过（quick-top-threshold-fixed-build.txt），维持0错误、38既有警告、1提示；最终测试APK编译通过（quick-top-threshold-boundaries-build.txt）。

已检查quick-top-threshold-short.png和quick-top-threshold-distant.png，前者只有一条偏移、按钮隐藏，后者超过320dp夹具视口后出现，圆面大小与底部间距沿用既有实现。手机1618704c上核对安装包与新Debug签名一致后install -r覆盖，版本参数保持0.1.9/1010，未卸载、清数据或向真实社区发送内容。

缩小后的首轮35项相关UI回归有34项通过（artifacts/ui-polish-final-ui.txt），新增实际点击圆面外4px用例复现32dp玻璃子层未响应自动触摸扩展。使用独立48dp点击层修复，避免假设复杂pointerInput组合自动提供扩大命中范围。最终补测12项全部通过（artifacts/ui-polish-touch-final-ui.txt）：公共回顶9项、网页初始等待1项、Boost灰色与已点赞红色2项；合并首轮未改区域，共37个不同UI用例通过。离线详情截图ui-polish-topic-actions.png已检查未点赞爱心/计数与Boost同灰、32dp圆面和12dp操作栏间距。

缩小后的Debug、测试APK、Lint构建通过（artifacts/ui-polish-touch-final-build.txt），Lint保持0错误、38既有警告和1提示。最终在同机重新查看quick-top-material-LIQUID/BLUR/TRANSLUCENT.png，检查三种材质、图标和底部避让。真实首页只读滑动截图ui-polish-home-scrolled.png检查32dp圆面及tabbar约12dp可见间隙；按实际图标中心[540,2088]点击后的ui-polish-home-returned.png检查第一页首条可见、搜索页头展开、回顶按钮收起。真实详情动作使用离线夹具检查，不提交真实阅读计时。

最终Debug、AndroidTest和Lint构建通过（artifacts/quick-top-final-build.txt），Lint为0错误、38既有警告、1提示。27项JVM关联回归全部通过：ReplyRulesTest 3、TopicFloorNavigationTest 12、TopicResumeReadingTest 12。

最终26项真机用例通过（artifacts/quick-top-final-ui.txt）：QuickScrollToTopUiTest 9项、PublicProfileUiTest 17项。覆盖独立测试配置默认开启/持久化、开关即时生效、分页状态、覆盖和重复点击、公开主页展开、远楼层失败保留/成功回标题、三种材质避让，以及回复0/5/6/10字计数与发布阈值。此前另外40项页面回归通过（artifacts/quick-top-regression-ui.txt），包含首页、分类、个人中心、话题向前分页、编辑器生命周期、设置面板和应用内网页，共66项不同UI用例通过。

先复现外部回复面板打开时按钮未隐藏（artifacts/quick-top-reply-cover-repro.txt），再让话题目标同时遵守readingEnabled，最终新增用例通过。两条旧主页断言限定展开header祖先并显式定位热门主题，保留可见性、手势、资料面板和匿名登录回调检查，避免昵称多节点和固定滑动距离引起误报。

真机1618704c核对Debug签名后使用install -r覆盖，未卸载或清除账号数据。自动化仅使用离线夹具，不切换真实账号或发布内容。另在真实浅色首页检查首屏隐藏、滚动出现且避让底栏、点击回顶并恢复页头（artifacts/quick-top-home-before.png、quick-top-home-scrolled.png、quick-top-home-returned.png）。

同机深色130%字号的LIQUID/BLUR/TRANSLUCENT静止截图分别为artifacts/quick-top-material-*.png，已人工检查按钮居中和底栏间距，液态边缘采样真实双色内容，模糊/半透明遵循既有兼容效果。色块夹具用于材质检查，真实首页截图用于导航避让与页头恢复检查；未将两者表述为所有页面或旧系统运行验收。

包含缩小、独立点击范围、间距、灰色点赞、列表返回稳定和网页加载改动的Release构建通过（artifacts/ui-polish-release-final-build.txt），后续三色加载样式同版本重建也通过（artifacts/tricolor-loading-release-build.txt）。版本通过参数保持0.1.7/1008，仅arm64-v8a。当前正式包artifacts/release/0.1.7/kodo_0.1.7_release_arm64-v8a.apk已验证正式签名、非debuggable、包名/版本/ABI和16KB对齐（artifacts/tricolor-loading-release-verification.txt）；SHA256为ce85586d917fde84892e25900736c312e4291eb0748204e29d9d969ccf3923e6。真机沿用Debug签名install -r覆盖，未卸载或清数据；正式包保留供用户安装，没有覆盖手机现有开发版。

## Integration boundaries

首页与分类共用FeedPage目标，只有当前已停稳且匹配筛选条件的分页启用，标签复用同一列表。个人主页和个人中心的页头与列表回顶并行动画，页头收起但列表未离顶约一屏时不显示按钮。公开主页只注册当前分页，更多/个人资料/关注/徽章面板覆盖时禁用底层目标。

话题已包含顶部时直接动画回标题；有前部缺页时调用jumpTo(1)，目标窗口成功后回标题，跳转期间禁用按钮。失败保留原窗口并显示错误，再次点击可重试；固定主帖副本不能作为1楼已在当前窗口的判断依据。外部回复面板通过readingEnabled禁用目标。

搜索、分类目录、收藏、游客页面、通知分页、个人话题/历史/私信、草稿、账号记录、忽略用户、关于、徽章网格和等级详情均注册原有滚动状态，短列表没有位移时不出现按钮；删除/分类/邀请/许可等面板显示时禁用底层目标。

聊天反向列表按视觉顶部定位最早已加载项，最新消息位置不显示入口；输入栏之外的视口决定按钮位置。应用内网页使用WebView真实scrollY与动画scrollTo，错误遮罩时禁用、释放时移除监听，不改变网页内链接分流。

## Consequences

公共Host统一材质、位置和生命周期，页面保留原始分页、阅读曝光及嵌套滚动。新增滚动页面必须注册目标并声明遮挡条件，避免入口遗漏或覆盖误触。视口筛选依赖Compose布局与导航生命周期，未来新增多窗格需复查最大面积规则。远楼层回顶仍需要网络；顶部未成功加载前保留当前正文。真实玻璃观感需设备检查，语义断言不能证明折射。

阈值随当前滚动视口适配，不等于固定的物理屏幕高度。首次恢复到尚未测量的列表位置、或离屏内容高度后来变化时，前缀距离可能有估算偏差；不为按钮显示额外加载或测量全部历史内容。
