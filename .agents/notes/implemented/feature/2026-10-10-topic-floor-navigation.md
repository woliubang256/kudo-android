# Agent Note: 液态楼层索引与跳转

Status: implemented

## Problem

标题下的总楼层数字占用阅读区，长主题缺少当前位置和直接跳楼入口。主题已按楼中楼分组，列表索引不能直接当作楼层号。

## Decision

删除标题下“xx个楼层”，底部中间加入主题材质的液态玻璃按钮，显示当前真实楼层/最高楼层，并用LogoYellow半透明进度填充。以实际可见内容更新当前位置，Boost不占楼层。点击后在底栏内联展开数字输入、滑动选择和明确跳转，通过横向动画挤出与恢复两侧按钮；不使用Material默认表单外观。

最高楼层使用highest_post_number，缺字段时退回最大已加载楼层/stream长度。索引使用主时间线可见帖子的真实Post.number；正文标题与摘要不作为楼层，[只读楼中楼](2026-10-10-reply-timeline-and-preview.md)不更新原帖阅读位置。楼层按钮复用LiquidButton的真实Backdrop材质，黄色填充在胶囊surface内绘制；底栏两侧16dp、组间8dp，写回复与点赞/Boost按内容撑开，中央占余量。窄屏、大字号或长楼层号时写回复仅显示笔图标，仍保留语义，索引数字可适度缩小。

阅读中段取顶部可见正文楼层；主列表已经不能继续向后滚动、末个已加载正文仍在视口中，且没有后续分页、初始或向后加载及错误时，计数显示最后可访问正文的真实编号。短帖到底后仍同时可见较早楼层，继续取顶部会出现末楼 #7 已展示而计数仍为 5/7；仅在完整末尾切换，向回滚动即恢复顶部可见楼层。缺号时不强行改成 highest_post_number，加载页的末尾也不能当作整个话题的末尾。

已加载楼层直接定位主时间线。最初方案将预览缓存目标直接纳入主列表，以省去额外读取并立即定位；这会把远处楼层接到旧列表后，缓存跳转和窗口合并现由[连续楼层窗口](../bug-fix/2026-10-11-topic-jump-window.md)部分取代：目标页及前后各一页组成新主窗口，边翻边补中间页。目标不存在、权限不足或网络失败保留当前内容并显示错误。未过期的跳转请求失败时，除了保留选楼面板的 jumpError，还通过既有 events 发送同一条短提示，由话题页面现有收集器显示 KudoToast；回复对象、解决方案或讨论预览入口未打开选楼面板时也能看到失败原因。每次失败均发事件，不依赖错误字符串变化；取消或过期请求不提示。跳转请求互斥/取消过期结果，不以网络加载或滑块预览上报阅读。

帖子 3109398 的 11、12 楼都显式回复 pub4hs 的 10 楼；2026-11-04 浏览器公开 GET /t/3109398.json 与 /t/3109398/10.json 均没有返回 10 楼，定向请求只返回其邻近楼层。服务端返回 200 不代表目标存在，不能把邻近楼层误作跳转成功，也不能据此确定删除或权限原因。旧实现虽然生成 jumpError，但选楼面板关闭时没有可见反馈。

请求有独立代次，较新跳转取消旧请求，刷新也取消跳转；合并窗口保留已加载帖子上的确认写入状态。targetRevision确保重复跳同一楼层仍重新定位，刷新读取最新目标。跳转加载、面板遮挡和目标定位之前暂停阅读，不给中间加载窗口计时。到达末尾时视口可能同时显示较早的正文，这些实际曝光仍按既有阅读规则计时。

resolveContext取消旧父链请求后发现已无缺失父楼层时，明确清空contextLoading和contextError；否则新窗口补齐父楼层后会残留旧请求的加载态。

## Existing note audit

已检索楼层、深链、楼中楼和阅读。[楼中楼](../../implemented/feature/2026-10-10-nested-replies.md)与[自动续载](../../implemented/feature/2026-10-10-topic-continuation.md)保持父链和分页契约；[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)保持实际曝光规则，弹窗和跳转加载期间暂停。[回应](../../implemented/feature/2026-10-09-topic-author-and-responses.md)保留点赞与长按。无完全吸收或过时提案。

## Alternatives considered

- 使用可见列表index作为楼层最简单，但标题项、隐藏楼层与楼中楼会使其错误，因此使用Post.number及服务端highest_post_number。
- 始终取顶部可见正文便于稳定表达阅读位置，但短帖到底仍会停在较早编号；完整末尾改取最后可访问正文。直接显示 highest_post_number 能保证末尾满进度，但删除或无权限的末楼会被误报，因此保留真实可见编号。
- 跳转时从头逐页加载可以复用现有more，但大主题请求过多，使用现有带楼层GET读取目标窗口。
- 只把 jumpError 展示在选楼表单中能让输入与错误集中，但回复对象等入口不会打开该表单，点击失败便无反馈；保留表单错误并复用应用既有短提示事件。自动跳到邻近可见楼层能让页面有所响应，但会把无关内容当成被回复内容，因此只在真实目标存在时定位。
- 全黄色按钮更醒目，但会压过正文，使用主题玻璃底与半透明黄色进度，文字跟随主题保证清晰。

## Verification

TopicFloorNavigationTest覆盖真实最高编号、已加载和重复目标零额外GET、刷新保留新目标、未加载楼中楼补父链、缺号/403保留内容、过期跳转不写回，以及跳转期间零阅读写入。父链加载态问题先复现断言失败（floor-context-repro.txt），修复后测试通过。

回复入口失败提示的两项 JVM 复现先因未收到事件失败（artifacts/reply-jump-error-repro-unit.txt），修复后 TopicFloorNavigationTest 共 14 项通过；覆盖 11、12 楼连续回复同一个不可访问的 10 楼、403、503、取消请求不提示及窗口和目标修订号保持不变。真机 ReplyJumpFailureUiTest 使用隔离的只读离线接口，经实际点击回复对象、TopicViewModel、事件收集器和系统 Toast 完成端到端检查：旧版失败（reply-jump-toast-repro-device-v2.txt），修复后通过（reply-jump-toast-fixed-device.txt），截图 reply-jump-failure-toast.png 确认中文错误可读、正文保留、选楼面板关闭。Debug / AndroidTest 构建通过，Lint 为 0 errors、60 个既有 warnings、1 hint（reply-jump-fixed-build.txt）。

真机夹具验证数字范围、滑块预览零跳转、首末楼、重复跳转、楼中楼定位、失败保留、面板和加载期间阅读暂停。280dp/130%深色字号下检查三种玻璃材质的按钮间距、完整数字和拖动关闭；相关自动续载、阅读、Boost、楼中楼和液态按钮用例回归通过。测试只使用离线内容与本地回调。

短帖末尾计数的三项复现测试先在修复前 Debug 上失败（artifacts/topic-bottom-counter-repro-device.txt）：已展示末楼仍不显示 7/7、不可访问末楼时不显示 6/7，以及分页结束后不切换最后可访问编号。修复后 TopicFloorNavigationUiTest 与 TopicContinuationUiTest 共16项真机测试通过（artifacts/topic-bottom-counter-device-tests.txt），覆盖到底后回滚、向后分页待加载/失败、缺号末楼、远距离跳楼、向前续载和阅读暂停；截图 artifacts/floor-short-topic-bottom.png 确认同时可见 #5–#7 时显示 7/7。签名一致后使用 adb install -r 覆盖 Debug，保留设备数据。

最终59项相关JVM测试通过，Debug Lint无错误（30项既有警告、1项hint）。本批40项不同真机用例通过：回复/表单8项、楼层/续载/阅读/Boost/楼中楼/材质25项、作者/回应/更多7项；其中最初三个旧夹具断言或尾部视口假设失败，适配新入口与实际曝光后分别重跑通过。实际触摸滑块验证和首楼按钮通过（floor-drag-device.txt），浅色进度/跳楼弹窗、三种深色窄屏材质截图分别存于artifacts/floor-*.png。测试版以install -r覆盖安装，未清理账号数据，未向真实社区发送测试内容。

## Far jump continuation

此前在应用内跳转后仅选择目标后方的缺失ID，避免后续加载首批帖子使视口退回二十多楼；初始深链仍沿旧顺序恢复首楼。该单向特例现由[连续楼层窗口](../bug-fix/2026-10-11-topic-jump-window.md)的双向stream边界分页取代，首楼仍单独保留 pinnedOriginal，前插分页保持可见楼层位置。

## Consequences


楼层水印按最新界面要求使用 # 前缀、11sp 常规字重和12%前景色，独立叠放于整条回复最右上角，距顶2dp/右4dp，不受正文16dp padding影响。此前大号加粗且无前缀便于快速定位，但过于醒目，用户要求恢复为不起眼的水印。TopicReadingUiTest 真机验证外层边缘位置、字号及字重，8项阅读相关用例通过（artifacts/floor-window-final-device-20261011.txt）。

删除或不可见楼层会造成楼层编号空洞，按网站真实号码显示且明确反馈不可达；末楼不可访问时允许出现 6/7 这样的真实编号。长文当前位置表示当前可见楼层，完整末尾表示最后可访问楼层，不替代实际曝光计时，也不表示整篇已读完。

楼层跳转使用底部栏内联展开：展开时以同一 Backdrop 液态容器显示可拖动滑块、可编辑楼层数字和动作按钮，回复与回应/Boost 两侧控件通过横向动画退出。所选楼层与展开时相同显示关闭图标，点击收起并隐藏键盘，不调用跳转；目标改变才显示确认，改回起始楼层恢复关闭，非法输入仍禁用确认。确认定位完成后自动恢复原底栏，返回键可取消展开。楼层表单不创建弹窗窗口，阅读状态仍在展开、加载和定位期间暂停。

滑块复用 Backdrop 官方 catalog 的 `LiquidSlider`，轨道强调色采用 `LogoYellow`。楼层输入按最高楼层的位数测量宽度，保留总楼层与48dp确认按钮，滑块通过 weight 占满剩余空间；不再给数字区域固定140dp最大布局宽度。轨道整行48dp可点击，拖动保留官方液态镜片。单楼层话题使用有限动画范围，定位期间禁用触摸和语义调整。内联表单不参与模态窗口的语义和焦点屏蔽，否则会阻断输入与错误提示；正文曝光仍在编辑期间暂停。

镜片和填充条使用0..1连续位置，拖动立即更新，不等待父层取整后的楼层号回传。父层整数只用于文本和目标，拖动时不覆盖本地连续值；松手或轨道点击通过交互修订号对齐实际所选楼层，手动输入直接同步。动画对象不依赖轨道宽度，最新宽度只参与位移换算；新范围创建新动画实例时，手势协程也绑定该实例，避免保留旧闭包。

## Inline control verification

`TopicFloorNavigationUiTest` 继续覆盖滑块预览、输入校验、远距离定位、定位失败保留表单、阅读暂停和窄屏大字号；新增断言展开态隐藏 `topic-reply` 与 `topic-actions`，确认后恢复，并使用返回键验证内联控件关闭。

内联编辑期间底栏使用 IME inset 避让数字键盘，确认成功后收起键盘并恢复原底栏。`TopicFloorKeyboardUiTest` 在实际 edge-to-edge / adjustResize 窗口下先复现确认按钮被键盘遮挡（artifacts/floor-keyboard-repro-device.txt），修复后需保持输入与确认均在键盘上方。滑块当前值通过 rememberUpdatedState 参与观察，拖动累计保留连续浮点值，避免整楼取整丢失位移或输入变化后镜片停在旧位置。

2026-10-13：19项相关JVM测试通过；楼层导航、续载及键盘共17项不同真机用例通过（artifacts/floor-inline-final-device.txt 中16项，键盘用例修正短帖尾部计数假设后在 floor-keyboard-final-device.txt 单独通过）。覆盖黄色轨道像素、滑块余量宽度、触摸拖动与点击、输入同步、失败保留、前后分页和阅读暂停，以及键盘上方确认与恢复两侧按钮。已查看离线夹具浅色、深色窄屏及键盘截图 floor-inline-*.png。Debug、AndroidTest与Lint通过，Lint为0错误、43警告、1提示；以真机相同的0.2.2-dev / 2005及相同签名通过install -r覆盖，未卸载或清数据。

关闭动作的两项旧版复现失败（artifacts/floor-motion-repro-device-v2.txt），修复后FloorSelectorActionsUiTest两项真机通过（artifacts/floor-actions-device.txt）：未修改直接关闭零跳转、修改再改回恢复关闭零跳转。与系统返回共用关闭路径，保留键盘收起。

连续拖动先复现约40px移动但轨道边缘330→330px（artifacts/floor-motion-repro-device-v2.txt）。FloorSelectorMotionUiTest三项真机通过（artifacts/floor-motion-fixed-device-v2.txt）：同一整楼内也更新实际绘制、2/3/5/100000楼在4/8/16ms输入期间双向逐帧跟随、轨道点击和输入同步及单楼层有限值。两端语义边界会裁切镜片，逐帧光学中心检查位于轨道内部；端点另验证选中值与有限坐标。
