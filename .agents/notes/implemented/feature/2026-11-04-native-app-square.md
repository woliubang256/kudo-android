# Agent Note: 原生应用广场与展开搜索

Status: implemented

## Problem

用户澄清应用广场应为原生目录，具体小程序继续使用官网 H5。当前入口直接进入 /apps 网页，不符合原生列表、宫格、顶部液态玻璃操作和搜索向左展开的要求。小程序运行页的更多与关闭胶囊也需要更扁，并使用用户提供的微信式 SVG。

## Decision

应用广场通过现有 DiscourseClient 请求官网 `/mini-app-api/apps.json`，与登录会话、网络策略及标准错误处理保持一致。官网实际请求使用 `sort=<排序>&q=<query>&page=<零起始页码>`，响应包含 apps、page、per_page、total、sort 和 query；列表随官网分页，不只过滤已加载的一页。默认 latest（最新上线），另支持 hot（最热，近 7 天去重使用量）、rating（评分最高，同分按评分人数）和 used（使用最多，累计去重使用量）。切换排序保留查询、重置页码并取消及隔离旧响应；分页和重试继续使用当前排序。进入原生广场不创建 WebView，点击具体应用时打开其官网运行页，返回保留广场状态。

顶部依次为返回、应用广场标题、搜索、筛选、布局切换。原生内容与顶部采样源分离，复用 MaterialPageScaffold、TopBarIconButton 和用户选择的材质，匹配现有独立圆形玻璃操作。搜索点击后以右侧为锚向左展开，占用标题区域；返回、筛选与布局切换位置固定。展开后自动聚焦，提供清空，返回先收起搜索再离开目录；请求取消并隔离旧查询结果。筛选使用扁平底部面板，四种排序仅显示名称与当前黄色勾选，不增加解释小字；保留查询与布局。

两种布局分别为图标在左、名称在右的列表，以及图标在上、名称在下的自适应宫格。默认宫格，不额外堆放版本、发布人、评分及重复副标题。切换保留查询，布局选择本地记忆。支持浅深色、大字号、加载、空结果、失败重试和后续分页。参考图用于布局与操作，不虚构“最近使用”“我的常用”等站点没有提供的数据分组。

“我的”页面的应用广场入口沿用用户提供的小程序图形：访客使用中性灰，登录后与其他彩色账号菜单并列使用微信绿 `#07C160`，不以入口颜色限制访客访问。

H5 运行页仍使用普通材质，不把网页重新接进玻璃采样。胶囊可见高度 32dp，每个操作保留至少 48dp 触控区；更多采用用户 SVG 的中间大点、两侧小点，关闭采用圆环加实心点，20dp 图标与半透明配色遵循[小程序网页容器](2026-11-04-mini-app-square.md)的最新参数。原有关于、全屏、剪贴板和退出顺序保留。

## State and navigation

数据单元提供 MiniAppEntry、miniApps 请求与 AppSquareViewModel。页码必须与请求一致，导航仅使用有效 UUID；不安全图标退回通用图标。搜索延迟 300ms、取消旧作业并使用代次隔离，翻页保留已完成数据，失败重试同一页并按 ID 去重。ViewModel 绑定导航条目及账号键，不跨账号复用结果，不在公共目录模型保存用户资料。布局偏好使用 AppSettings 的 `app_square_grid` 键，仅保存显示方式。

应用详情按所选 UUID 独立请求 `/mini-app-api/public/<app-id>/info`，共用[小程序公开信息解析](2026-11-04-mini-app-square.md)，不从目录拼装另一套关于数据。详情请求与目录请求分别取消和隔离，关闭、换应用、换查询或排序后，旧请求不能重开弹窗或覆盖新应用。缺失使用次数按用户要求省略，不补查目录或创建运行中的 WebView。

列表与宫格的长按通过同一 combinedClickable 打开关于，普通点击继续进入应用。长按与打开筛选前清除搜索焦点并收起键盘，保留展开状态、查询和布局，避免输入法遮挡弹层。关于与筛选复用 EditorToolBackdropHost 和 EditorToolSheet，以不包含弹层的目录兄弟层为采样源，跟随当前三种材质。加载、失败重试和关闭保持可用；关于内容与小程序运行页共用 MiniAppAboutContent，但运行页外壳仍强制普通材质。

列表和宫格共用 LazyGridState，返回 H5 来源时保留查询与滚动位置。查询变更的 revision 仅应用一次并保存已应用值，不能在屏幕每次重新进入组合时无条件滚回顶部。官网精确 /apps 路径的原生入口、正文链接和外部导航均指向原生 apps 路由，具体 /apps/<UUID> 保留完整网页 URL；WebView 内的跳转仍遵守网页连续浏览规则。

## Existing note audit

[应用广场与小程序网页容器](2026-11-04-mini-app-square.md)部分重叠：本篇替代“目录入口直接打开官网 H5”的决定，保留该篇关于、受限桥、全屏和生命周期的独立约束；两篇互链，不归档整篇。[顶部材质](2026-10-16-page-toolbar-material.md)复用其兄弟采样源及顶部操作契约；[网页普通材质](../bug-fix/2026-10-29-webview-ordinary-material.md)保持。未发现同主题未实施或被否提案。

## Alternatives considered

- 复用官网目录 H5 可以沿用站点搜索和分页，维护成本最低，但不能满足用户明确要求的原生布局和搜索动效，因此目录改用原生，继续复用 H5 执行小程序。
- 只对首批应用做本地搜索能减少请求且当前应用数量少，但官网已有分页，后续会漏掉尚未加载的应用；采用官网搜索与分页，并取消旧请求。
- 给整个页面和运行中的 WebView 套同一玻璃容器能让外观一致，但违反既有网页普通材质决定并重新引入采样风险；目录使用原生材质，运行页继续普通材质。
- 从目录补查使用量能丰富关于信息，但详情没有该字段且用户明确要求缺失就省略，因此只使用详情，不额外合并两个接口。

## Testing

- 2026-11-05 浏览器只读核对 hot / latest / rating / used 四种官方参数；详情 app 有 rating_average、rating_count，没有使用次数字段，契约证据为 `artifacts/app-square-statistics-contract.json`。AppSquareTest、AppSquareAboutTest 和 MiniAppInfoTest 共 14 项 JVM 测试通过，覆盖排序参数、查询编码、分页重置、慢旧分页隔离、详情失败重试与身份校验、关闭后旧结果隔离，以及缺失和异常评分字段。
- 线上截图发现搜索后长按仍显示输入法；新增 longPressFromSearchHidesTheKeyboardBeforeShowingAbout 在旧实现的键盘隐藏等待上失败，记录为 `artifacts/app-square-keyboard-repro-device.txt`。长按入口改为先清焦点、隐藏输入法，再加载关于。
- 新增 AppSquareMaterialUiTest 对关于与筛选分别切换目录实际绘制的蓝／红底色，采样限制在圆角面板内部，排除角落的遮罩区域，既检查实时采样，也要求普通材质保持高遮盖率。面板与采样层的父链互不包含。早期无缓存模拟图标的夹具出现 Espresso 等待空闲超时及 Compose 并发快照异常，分别保留 `artifacts/app-square-about-crash-logcat.txt`、`artifacts/app-square-material-idler-device.txt`，不把这些运行记为通过；最终材质测试使用确定的目录底色，真实图标和详情网络链路单独由线上探针验收。
- 最终 15 项真机测试全部通过（53.57 秒），见 `artifacts/app-square-final-device.txt`：320dp／130% 字号的搜索与固定按钮、列表／宫格长按、普通点击、评分缺失／零人／小数星、详情失败重试、搜索后键盘隐藏、四种排序保持查询和布局、浅深色三种材质采样及源排除、运行页关于与 Neo 头像回归。AppSquareLiveProbeTest 使用真实官网会话请求目录、2048 详情及四种排序，未启动小程序或提交评分；`artifacts/apps-live-about-final.png` 已目视核对真实图标、五星、2 人评分和中文，输入法已隐藏。小数星由离线 4.35 分夹具截图核对，不冒充线上分数。
- Debug / AndroidTest 构建及 Lint 通过，日志为 `artifacts/app-square-final-build.txt`，Lint 为 0 errors / 68 warnings / 1 hint。设备仍为 RMX2202 / Android 13，开发版 0.3.1-dev / 3002，覆盖前版本与 SHA-256 一致；仅使用 install -r、install -r -t 和 am instrument，首次安装时间仍为 2026-10-23 14:55:34，未清数据。UTF-8 与 diff-check 通过；核对接口的自建浏览器页已关闭。
- 应用广场入口微调后，AppSquareEntryUiTest 在浅深色下依次切换访客、登录用户、访客，6 次打开均不触发登录；记录为 `artifacts/mini-app-entry-tint-device.txt`。已检查 `artifacts/apps-entry-guest-dark.png`、`artifacts/apps-entry-member-dark.png` 和 `artifacts/apps-entry-member-light.png`，访客灰色、登录用户微信绿且文字可读。
- 浏览器捕获官网实际目录及 `q=2048` 搜索请求，确认零起始分页、latest 排序和响应字段。
- AppSquareTest 验证请求参数编码、页码、UUID 与图标约束，分页去重与同页重试，以及慢旧请求不能覆盖新查询；LinuxDoLinkTest 验证只有目录进入原生路由，具体应用继续 H5。三组相关 JVM 测试共 15 项通过。
- AppSquareUiTest 覆盖 320dp 窄屏、1.3 倍字号、浅深色和三种材质下搜索向左展开、按钮位置稳定、清空、聚焦、返回折叠，布局切换、打开应用后恢复查询和滚动、空状态及失败重试。返回位置问题先由 `artifacts/native-apps-ui-repro-device.txt` 复现，再修复 revision 重复应用。
- 34 项相关真机回归通过，包含目录、入口、深链和 WebView 普通材质及小程序原有能力，输出为 `artifacts/native-apps-final-device.txt`；目录、搜索及胶囊截图用于核对中文可读与真实内容采样，不采样运行中的 WebView。最后的搜索框对比度与胶囊反馈微调后，10 项受影响界面用例再次通过，见 `artifacts/native-apps-refined-device.txt`。
- AppSquareLiveProbeTest 已通过手机实际会话与网络链路加载线上目录，输入 2048 后返回一个对应应用，见 `artifacts/native-apps-live-device.txt`、`artifacts/apps-live-result.txt`。截图另检查布局切换后的界面已完成组合，避免把网络返回与实际显示混为一谈。
- 设备 RMX2202 / Android 13 / WebView 156，保持开发版 0.3.1-dev / 3002；覆盖前核对版本和 SHA-256 一致，使用 `adb install -r` 及 `adb install -r -t`，未清理用户数据。构建、安装前检查改动的 UTF-8 中文。
- Debug / AndroidTest 构建与 Lint 均通过，Lint 为 0 errors / 67 warnings / 1 hint；笔记门禁通过。搜索框只裁切内部文字与操作，不裁切玻璃阴影，避免展开后出现矩形阴影边界；该微调后 3 项目录界面用例再次通过，见 `artifacts/native-apps-final-polish-device.txt`。

## Consequences

目录能独立提供原生布局与交互，但需要维护官网目录 API 的字段及分页契约；错误页提供重试与网页验证，避免静默显示不完整结果。普通 H5 运行时仍由官网维护鉴权和沙箱。新增目录不包含最近使用、个人收藏等站点未提供的分组。原生顶部复用全局材质，小程序网页与弹层保持普通材质。

页面回退恢复限于保留的导航栈，进程重建会重新请求目录；本地布局偏好跨重启保留。当前目录不足一页，线上多页情况依靠契约夹具验证。手机原生目录已通过在线验收；H5 运行页此前的连接超时记录仍保留在运行时笔记中，不能从目录成功推断具体小程序线上运行或用户权限已全部验收。目录探针仅在显式 `appSquareLive=true` 时访问真实接口，不评分、不举报、不发布。
