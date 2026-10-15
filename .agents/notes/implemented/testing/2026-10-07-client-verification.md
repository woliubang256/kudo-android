# Agent Note: 客户端契约与原生界面验证

Status: implemented

## Problem

社区接口会返回验证页和限流，真实写操作又不能作为自动测试数据。需在可控环境确认异常分支，并将视觉验证与网络可用性分开。

## Decision

当前底栏使用[Backdrop 官方示例](../architecture/2026-10-07-backdrop-demo-navigation.md)。新增真实触摸测试区分语义点击与实际指针事件，检查隐藏采样不暴露重复入口，以及从发布或未选中标签开始拖动的隔离行为。

详情操作栏的 SurfaceLiquidUiTest 使用独立夹具回调，不向社区点赞、收藏或回复。真实触摸检查写回复、赞、收藏与分享分别一次；右侧玻璃容器没有第四个点击入口。检查关闭/忙碌/缺主帖权限、按压和取消实际内容尺寸、按下过程中停用并重新启用，以及深色130%三种材质。字号判断使用每个字符的实际边界；段落的可用宽不代表字形溢出，不能仅用 didOverflowWidth 判断。

设置模态使用 SettingsModalUiTest 夹具，不退出真实账号或打开外部链接。检查选项唯一与选中语义、即时保存次数、取消/遮罩/系统返回键、背景可访问入口隐藏、面板空白不关闭、退出必须确认且退出动画中不重复、三种材质深色130%、短窗口内容滚动与固定动作，以及连续Tab焦点留在弹窗。键盘用例显式请求 InputMode.Keyboard，合成按键不会自动切换手机的输入模式。材质和同窗口采样契约见[玻璃模态笔记](../feature/2026-10-08-glass-settings-modals.md)。

JUnit 与 MockWebServer 检查请求来源、CSRF、失败不重试、JSON 与 HTML 解析。FeedViewModelTest 先复现分类 429 误伤话题列表，再修复至通过。Compose 仪器测试使用明确的测试内容，验证玻璃导航、帖子动作和主题切换；测试内容不进入正式应用数据源。

[头像](../feature/2026-10-08-avatar-presentation.md)的动画测试使用自行生成的三帧APNG/GIF/WebP，读真实像素证明换帧，不能仅检查Drawable类型。头像UI夹具提供本地图片抓取器与表情目录，检查方圆裁切、头像外角标、账户/公开场景、静态回退和状态到期。后台测试真实切换Activity到CREATED，再恢复RESUMED；只改一个Compose布尔值无法复现后台重组停止。UI测试在应用加载器测试前运行，验证临时加载器还原和Activity重建不受初始化顺序影响。

按用户要求，后续测试只使用已连接真机。通过 adb 指定序列号安装测试 APK 并运行 instrumentation，保留主应用数据与网页验证 Cookie；测试截图留在 external files，运行后拉取人工检查。测试前停止 Android CLI 的布局 instrumentation，避免两个 UiAutomation 争用同一设备。截图等待系统对话框退出动画完成，避免将过渡帧误当成页面结果。

深色模式增加实际 TextLayoutResult 前景亮度断言，弥补仅验证主题枚举改变却未检查文字可读性的缺口。

导航拖动增加冻结 Compose 时钟的几何检查，比较松手前后的实际胶囊位置，再检查发布入口与取消拖动能回原标签。只验证功能不变量，不把字号、时长等样式常量复制到测试里。系统动画关闭验证通过真机无障碍界面的“关闭动态效果”开关完成，读取三项动画缩放为 0 后运行仪器测试和只读浏览，再恢复开关。此设备限制 adb 直接写系统设置，失败后改用系统界面。

滑动预选测试读取实际 TextLayoutResult 颜色，比较当前、经过与未经过标签；同时检查 selected 在松手前不变，发布入口不触发动作，松手或取消后高亮收敛。在浅色与深色 130% 字号下运行，透明材质另用截图和真实慢拖录屏人工检查，不把颜色常量复制到断言中。交互依据见[酷安密度与过渡对照](../feature/2026-10-07-coolapk-density-and-motion.md)。

用户指出高亮仍偏棕、选中黄底和镜片过矮后，增加三个实际渲染检查：文字颜色与品牌 LogoYellow 比较，标签上半部的图标像素应包含品牌黄；纯白背景的静止选中面检查 RGB 色偏，允许不同的中性灰强度；拖动镜片比较实际上下边界与标签高度，取消后应收回。真实前景折射与裁切另用同机静止截图和慢拖录屏人工检查，完整液态效果的结果只归属于已测 Android 13 真机。材质与图层契约见[玻璃导航](../feature/2026-10-07-liquid-glass-ui.md)。

整栏抬升与中心放大增加两项回归：读取变换后的 boundsInRoot 比较拖动前后的可见尺寸，检查镜片中心仍跟随手指、镜片仅略高于放大后的标签，取消后恢复原尺寸；捕获完整 Compose 根画面，比较同一图标在静止与镜片覆盖时的实际像素宽度，并与镜片外图标比较。只捕获标签子树会漏掉前景镜片，只读取未裁切布局宽度也不能衡量图层缩放。白色夹具的两侧 20dp 边距与正式首页一致，具体光学比例仍由同机录屏校准。

NavigationMaterialUiTest 检查首尾灰面外侧与上下的实际留白相等，覆盖两种宽度/字号和三种材质。黑底根画面采样判断白面强度，真实移动并确认整栏放大后采样动态透色，取消恢复；黑底不作为浅色标签可读性验证。新增多位置停顿几何检查时，逐帧推进Compose时钟并等待真实16ms，因为官方VelocityTracker使用墙钟；瞬间推进虚拟900ms不代表速度采样经历了停顿。保留原凸出范围断言，系统关闭动画单独复现速度形变后再修复，材质判断仍结合真实正文和图片。

CommunityUiTest 的整栏抬升与取消复位检查同样按真实16ms间隔推进60帧后读取几何。只用 waitForIdle 加速动画会在很短墙钟内输入整段位置序列，使速度形变残留，偶发把稳定镜片误测为低于底栏。测试统一时钟后继续沿用原位置、凸出上下限与恢复尺寸断言，不改变生产动画或放宽验收范围。

## JVM fixture lifecycle

0.2.6后续云端运行38742475340中前述断线与搜索用例通过，502项仅HomeUpdateViewModelTest首项报告UncaughtExceptionsBeforeTest。完整XML的suppressed异常明确为取消中的IO协程在Dispatchers.resetMain之后恢复Main（`second-tag-test-failure/test-results/testDebugUnitTest/TEST-com.kudo.android.HomeUpdateViewModelTest.xml`）。时间戳显示此前运行FeedViewModelTest；该类三处清理只cancel而未join，与已修复的FeedUpdates夹具属于同一生命周期缺口。FeedViewModelTest共用的清理函数等待viewModelScope根Job cancelAndJoin后再关闭服务器与重置Main，新增受控取消清理用例守住顺序，不修改HomeUpdate业务逻辑。

新增清理用例先失败，报告为 `artifacts/release/0.2.6/feed-teardown-repro.xml`：旧清理函数在受控清理放行之前已经返回。等待根Job结束后，FeedViewModel、FeedUpdates、FeedSession、FeedPageCache与HomeUpdate共24项通过（`feed-teardown-fixed.log`）。XML未保留原IO协程的创建栈，不把具体哪次请求视作已确认事实；用受控清理回归约束实际发现的生命周期缺口。

0.2.6云端运行38737113799的首轮502项JVM中，断线后释放并发与新搜索覆盖旧请求两项失败；第二轮断线用例再次失败。首轮XML明确显示断线后的第二次请求连接 `localhost/::1` 被拒绝，而MockWebServer只监听另一地址族。断线或取消会影响OkHttp路由选择，关闭自动连接重试的夹具不能假定localhost始终指向服务器监听地址。RequestScheduler断线夹具与SearchViewModel共享夹具显式绑定回环地址，URL使用同一 `listenAddress.hostAddress`，使请求始终到达同一个监听端点，保留响应体异常、请求数、并发释放和旧搜索不能覆盖新结果的断言。搜索用例原报告仅有超时，不能据此声称其底层异常也已确认是IPv6拒绝。

本地为原断线用例显式提供IPv4监听与localhost双栈DNS，先复现第二次请求连接 `::1` 被拒绝，失败XML与临时夹具差异保留在 `artifacts/release/0.2.6/dual-stack-repro.xml`、`dual-stack-repro.patch`。同一双栈输入下改为监听IP后，两组18项通过；最终提交移除DNS注入，夹具仍绑定同一回环地址并使用其IP。完整502项JVM与Lint在两核、IPv6优先配置下通过，日志 `full-unit-lint-fixed.log`；测试输出强制重新执行，不把UP-TO-DATE算作重复验证。搜索等待在失败状态立即断言网络错误，避免将明确的请求失败隐藏成等待结果超时；不延长5秒边界。

0.2.4云端运行38362477072的357项JVM中两项失败，完整XML与日志保留在 `artifacts/release/0.2.4/cloud-failure-1/`。点赞取消用例期望刷新楼层3却得到空列表，原夹具通过500ms真实socket延迟制造顺序，未记录失败状态，不能据此确定底层传输根因；顺序契约采用现有可控Call回调模式验证取消和迟到结果。ThreadAndDecorationTest的预览转主时间线用例期望总请求数3、实际4，断言覆盖了与跳楼无关的异步作者资料读取；请求数量契约限定目标 `/t/5/3.json`，并显式让作者请求在跳楼过程中进入计数，以复现错误边界。

作者请求受控放行后，本地先稳定复现同样的期望3/实际4（`thread-count-repro.xml`），修复仅按目标接口计数，保留连续楼层、目标修订号及唯一跳楼读取断言。点赞用例使用单线程Main、可控Call回调，确认刷新取消旧Call、先完成最新结果、随后交付旧结果不能覆盖、新账号独立列表，并等待两作用域cancelAndJoin后重置Main。11项关联测试及两核同JVM配置10轮共110项通过（`fixture-fixed.txt`、`fixture-repeat-1.txt`至`fixture-repeat-10.txt`）；完整357项JVM通过，日志为同目录 `full-unit-lint-fixed.txt`。未改App行为，未延长原网络等待或删掉原状态断言。

0.2.0 云端运行 38205318965 的完整 XML 已保存至 `artifacts/release/0.2.0/cloud-repair/floor-failure-report.xml`；311 项 JVM 仅 aSupersededJumpCannotReplaceTheLatestTarget 失败，明确超时于取消80楼后等待100楼完成（TopicFloorNavigationTest.kt:228），不是初始化或释放旧请求阶段。先在等待失败时记录完整夹具状态和请求路径，进行两核重复运行定位，不增加超时或移除旧响应不能覆盖新目标的断言。

补充状态诊断后，本地两核同一 JVM 50 轮共 600 项楼层测试通过（`cloud-repair/floor-diagnostic-repeat.txt`，测试 9.297 秒），没有复现云端底层传输失败。该顺序用例通过 DiscourseClient 已有 Call.Factory 入口使用可控异步 HTTP 回调：确认80楼请求已挂起、发起100楼后断言旧 Call 已取消，显式完成100楼并等待跳转，再从 IO 回送80楼迟到响应，检查目标、修订号和内容均不被覆盖。控制取消后仍能返回 onResponse 的真实竞态契约，不使用 sleep 或延长超时；其他楼层请求仍使用原网络夹具。不能据此声称已确定云端 socket 失败的底层根因。

可控回调修复后，12 项楼层回归及两核同 JVM 50 轮共 600 项通过（`cloud-repair/floor-controlled-fixed.txt`，重复测试 5.340 秒）；目标断言、取消断言及旧楼层不入缓存的断言全部保留。云端运行 38206575089 的完整 JVM、Lint、正式 R8 与发布全部通过（`cloud-repair/job-114671263366.log` / `job-114675392669.log`），实际发布附件也完成验签和版本复验，见[发布验收](../process/2026-10-08-signing-and-tag-releases.md)。

云端v0.1.8在FeedViewModelTest启动前报告未处理异常，具体被抑制异常尚未取得；同次楼层跳转测试出现等待超时。两核本地连续运行相关三组共200个测试未复现原失败，不据此宣称根因已确定。排查发现FeedUpdates夹具关闭时只cancel、不等待协程结束就重置Main和关闭线程；新增用例挂起取消清理，检查关闭动作必须等待清理完成。楼层网络夹具的动作及状态延续统一在独立单线程Main执行，与App的串行UI线程一致，避免Unconfined让网络回调与测试动作并发操作ViewModel；网络等待仍为5秒，不以延长等待规避失败。

取消清理用例先失败，报告为 `artifacts/feed-fixture-cleanup-repro.xml`；修复后21项相关测试通过，日志为 `ci-fixture-focused-fixed.txt`。串行Main还复现旧父链断言过早读取状态（期望1/20/80，实际1/80），报告为 `ci-fixture-focused-failure.xml`；等待真实父楼层出现后保留原断言。旧跳转用例在最新100楼完成后释放80楼请求，确认旧请求已返回才检查目标仍是100。两核同一JVM连续10轮共210项通过，日志为 `ci-fixture-repeat-fixed.txt`；这证明这些夹具不变量，并不能补回v0.1.8缺失的完整异常报告。

## Alternatives considered

全局设置JVM仅使用IPv4最省改动，但会改变所有网络测试的地址族行为，掩盖其他用例的双栈问题；仅给这两个失败夹具使用真实监听IP，不修改App的DNS、路由或重试策略。增加超时不能使未监听的IPv6端口成功，不用于修复本次ConnectException。

仅增加超时或重新运行的优势是改动小、偶发慢机可能通过，但无法消除任务越过测试边界或并发访问的机会；用明确清理完成条件及串行Main缩小调度差异。原始云端报告缺失的问题由发布工作流失败上传XML/HTML解决，原始失败与本地新复现需分开记录。

继续用服务器端 CountDownLatch 固定跳转响应的优势是涵盖真实 socket 取消，但等待的是服务端 dispatch 返回而非客户端处理完成，且明确的楼层顺序断言被底层网络失败转化为超时；此语义用例改为可控 Call 回调并加强取消、迟到响应和修订号断言，网络解析与分页仍由其余 HTTP 用例覆盖。

- 只在真实站点自动化：最贴近线上，但会受到限流和会话影响，也可能发出真实社区写操作；真实请求仅做只读验证。
- 只测静态页面：可以检查配色，却不能发现 JSON、CSRF、分页与状态竞争；同时保留网络契约测试。
- 断言 blur / lens 参数与固定高度：运行快且失败位置明确，但不能证明图标进入折射源、镜片未被父层裁切，也无法发现浅色主题使用错误的语义色；检查实际文字、像素和几何，再看真实慢拖材料。

## Consequences

网络错误可稳定复现，仪器测试可以生成可视化检查材料。真实账号、Cloudflare 和真机帧率仍需独立验收；不能用模拟测试通过来宣称其已验证。

## Verification

2026-10-09 图片相册与来源过渡：93项JVM通过；RMX2202 / Android13完整41项真机回归85.027秒通过，另1项补齐相册后不翻页直接缩回的像素专项2.619秒通过。系统关闭动画5项9.794秒通过，结束三项缩放恢复1。Debug/测试APK和Lint通过，0 errors / 28 existing warnings。日志为 gallery-final-build.txt / gallery-final-instrumentation.txt / gallery-retained-painter-check.txt / gallery-reduced-motion.txt。

相册补齐的时序测试使用原生MotionEvent并冻结下一次Compose重组，先复现真实横滑越界，再复现旧单图Pager把当前第5张重置到第1张；普通Compose触摸助手隐式同步会掩盖第一个问题。最后一指抬起NaN、原图重试、正文返回被底栏遮挡、缩放被相册补齐重置均先复现后修复。失败与修复日志、真实录屏和夹具区分见[相册](../feature/2026-10-09-image-gallery-and-transitions.md)及[来源过渡](../feature/2026-10-09-image-origin-animation.md)。中断续作后构建脚本恢复显式Debug签名环境，设备拒绝了一次签名不符的测试APK，重新签名后覆盖安装成功，主应用数据保留。

分发APK为 artifacts/kudo-image-gallery.apk（22,118,212字节），构建、分发与设备已安装APK的SHA256一致：4e2eeac0af2942de1c11244c669512de87c21d765717d8cae019d1a6cb8b8b8e，记录 gallery-apk-verification.json。真实列表和正文均完成第1张打开、第3张返回；尺寸/文件信息只出现在长按菜单，静态比例与转场帧分别查看。录屏不作为帧率基准。

2026-10-09 详情作者、回应与行内表情：先复现emoji丢图、其他回应误当爱心、撤销路径错误、服务器返回值被忽略、分页覆盖状态及深链主帖旧副本覆盖；完整76项JVM通过。RMX2202 / Android13的详情、Surface、头像和社区28项通过，59.081秒，写回复内容靠左收尾后Surface 5项再通过，10.395秒。Debug/测试APK与Lint通过，0 errors / 28 existing warnings。日志为 topic-emoji-repro.txt / topic-reaction-repro.txt / topic-state-repro.txt / topic-pinned-repro.txt / topic-final-build.txt / topic-final-instrumentation.txt / topic-aligned-instrumentation.txt。真实站点只读截图、尺寸测量、APK安装哈希见[详情记录](../feature/2026-10-09-topic-author-and-responses.md)，不把夹具回应写入当作线上变更验收。

2026-10-09 导航几何测试时钟：18项导航回归中旧抬升检查失败，镜片171.05px低于底栏176.40px，日志 `artifacts/navigation-rims-regression.txt`。该检查改为与既有多位置停顿测试一致的真实逐帧等待后，独立用例通过，2.985秒，日志 `navigation-rims-clock-fixed.txt`；原尺寸上下限与取消恢复断言全部保留。

2026-10-08 动态头像与角标：57项JVM及46项真机回归通过（101.177秒），Debug / 测试APK与Lint通过，0 errors / 28 warnings。头像后台未停和全局加载器初始化顺序崩溃先复现，再由9项专测及完整回归确认修复。输出为 avatar-ui-instrumentation.txt / avatar-lifecycle-instrumentation.txt / avatar-final-build.txt / avatar-final-instrumentation.txt。真实头像形状、资质和透明原图另行核查，APNG/GIF/WebP换帧使用确定性样本证明；设备、图片来源和兼容性边界见头像笔记。

2026-10-08 静止留白与透色：首尾与黑底复现两项失败后修正参数；关闭动画又发现速度残留压扁/拉高，独立复现失败后增加系统缩放观察。最终28项正常动画63.441秒、关闭动画51.013秒全部通过，输出 `artifacts/navigation-material-final.txt` / `navigation-material-reduced-motion.txt`，结束三项缩放恢复1。Debug / 测试APK和Lint通过，0 errors / 27 warnings；真实首页、图片经过底栏、拖动双高亮和末端静止截图已人工检查。新增测试的虚拟时钟与墙钟不一致记录为 `navigation-material-initial.txt` / `navigation-material-clock-fixed.txt`，几何断言未放宽。

2026-10-08 设置玻璃模态：新增7项与原有18项共25项真机测试全部通过，正常动画45.988秒、系统关闭动画38.548秒，输出 `artifacts/modal-final-instrumentation.txt` / `modal-reduced-motion.txt`；结束三项系统缩放恢复1。Debug / 测试APK与Lint通过，0 errors / 27 warnings。6项初轮的3个误报均为测试时序、虚拟列表或圆角外坐标；增加有效条件后通过。焦点用例显式键盘模式后通过，没有为测试误报改产品行为。浅/深色130%、三种材质截图已人工检查。

2026-10-08 详情 Surface Liquid Button：新增5项与原有13项共18项真机测试通过，28.73秒，输出为 `artifacts/surface-final-instrumentation.txt`。Debug / 测试 APK 与 Lint通过，0 errors / 27 warnings；三种材质的深色130%、实际触摸、禁用恢复与取消均使用夹具验证。初轮字号断言失败记录为 `surface-button-instrumentation.txt` / `surface-overflow-repro.txt`；实际文案未裁切，改用字符边界后通过。

系统关闭动画后的完整18项通过，25.076秒，输出为 `surface-reduced-motion.txt`；结束确认三项缩放恢复1。截图等待调整后按钮5项再通过，10.258秒，输出为 `surface-screenshot-instrumentation.txt`。官方 demo Buttons 页与夹具动静、浅/深色130%详情及最终安装后的真实滚动截图均已人工检查，具体材料见 TASKS.md。

2026-10-08 官方示例接入：RMX2202 / Android13 的13项 Compose 测试全部通过，正常动画23.749秒，系统关闭动画17.544秒。三项全局动画缩放通过系统界面关闭并恢复为1。Debug / 测试 APK、Lint通过，0 errors / 27 warnings。当前几何测试读取 boundsInRoot，包括镜片在内的真实缩放边界；镜片高度与整栏表面比较，并允许官方弹性面板的少量横向偏移。中心放大仍读取完整根画面像素。输出为 `artifacts/backdrop-final-instrumentation.txt` / `backdrop-reduced-motion.txt`；同机 demo / 酷DO慢拖及浅深色截图已检查，来源与参数见上述笔记。

最终分发 APK 为 `artifacts/Kudo-0.1.0-debug.apk`，覆盖安装成功；构建、分发和设备安装包的 SHA256 相同，最新记录为 `navigation-insets-apk-verification.txt`，完整上游 Apache-2.0 文本进入 `assets/licenses/`。本轮只调整底栏几何、材质和低动态速度形变，18项协议 JVM 测试沿用此前结果。详细命令、材料与最终哈希见 TASKS.md。

### 此前自绘底栏的回归记录

2026-10-07：此前 18 项 JVM 测试已通过，本轮 Debug 构建和 Lint 通过（0 errors / 26 warnings）。RMX2202（Android 13 / API 33、WebView 153）本轮正常动画下运行 12 项 Compose 测试全部通过，21.31 秒；前轮系统动画关闭时的 6 项通过，本轮未重跑关闭动画的 12 项测试。截图确认原生帖子和深色 130% 字号可读，摘要 / 正文基线间距分别约 22 / 32dp。首页、分类列表、分类话题及多楼层详情已使用真实 API 验证。分类 429、规范地址 301、对象标签、访客搜索、深色默认文字、胶囊松手跳回、首个拖动事件重复位移、滑动预选未高亮、品牌色、选中面色偏、镜片高度、整栏不放大与错误的整体反向采样均先复现失败再修复。详细命令和限制见仓库 TASKS.md。

本轮失败复现为 artifacts/navigation-scale-repro.txt / navigation-center-repro.txt，完整回归为 navigation-scale-instrumentation.txt。navigation-scale-before-after.png、navigation-raised-lens.png / navigation-material-dark-large.png 截图与 navigation-glass-coolapk-motion.mp4 / navigation-glass-kudo-motion.mp4 同机慢拖确认整栏放大、镜片中心图标和文字继续放大、前景弧面边缘折射、双高亮和松手收敛；navigation-glass-home.png 确认静止灰面和品牌黄且没有额外镜片白圈，结束留在真实首页。此前材质复现与 10 项回归保留在 navigation-material-repro.txt / navigation-material-instrumentation.txt，双高亮复现与 7 项回归保留在 navigation-preview-repro.txt / navigation-preview-instrumentation.txt。录屏仅验证动作与材质，不作为帧率基准。

较早在 MI 6X（Android 9 / API 28）安装启动正常，半透明兼容材质正常，但当时网络连接失败；未以旧机完成在线验收。用户换机后，不再运行模拟器。较早 API 36 模拟器结果仅为历史验证，不替代本次真机结果。

## Android CLI与仪器截图隔离

Android CLI layout会让com.android.cli.interact.instrumentation继续持有UiAutomation。随后仪器截图可能报already registered / Not connected并导致测试进程收尾失败；这不是产品布局失败。运行截图仪器测试前，仅停止这个CLI测试辅助包，保留应用数据和用户服务。2026-10-11排除冲突后13项相关测试通过，日志artifacts/runtime-notification-device-fixed.txt；角标的子节点在LiquidBottomTab合并语义中需使用useUnmergedTree检索，再检查唯一实际采样入口。
