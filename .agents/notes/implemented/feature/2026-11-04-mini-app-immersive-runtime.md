# Agent Note: 小程序沉浸运行视图

Status: implemented

## Problem

实际截图显示小程序运行页叠加了 App 返回和标题、官网导航和网页应用工具栏，正文仅占一部分空间。用户要求去掉原生返回与标题，进入后直接展示铺满的小程序，右上常驻更多和关闭。

## Decision

小程序入口使用沉浸布局，移除原生标题栏，保留普通材质的 32dp 胶囊并在右上悬浮，按钮继续提供 48dp 触控区。内容铺满系统安全区域内的可用屏幕，胶囊不额外占一整行。关闭回到原生目录并恢复来源状态；系统返回保留弹层、网页全屏与历史的现有退出顺序。

继续使用官网 H5 运行时和隔离 iframe。官网 loader 将 `embed_mode=true` 传递给运行 iframe，但单独该参数不能保证隐藏官网应用工具栏。入口携带该参数，并对精确应用路径中已存在的 `.mini-app-page .mini-app-loader` 做宿主布局适配，保持 DOM 位置和消息通道，隐藏其祖先链之外的站点外壳，保留官网权限 dialog。不得把 iframe 地址当作完整运行入口直接加载，以免丢失宿主的鉴权、资源校验和 SDK 消息通道。

WebPageScaffold 只切换标题与悬浮胶囊，正文保持同一 Compose 调用位置。不能按普通网页和小程序分支建立两个脚手架，否则站内 SPA 导航会重建 WebView，丢失页面历史与游戏进度。

同源 `/mini-app-api/frame-host` 的 CSP 不允许新增样式表；用 DOM style 属性调整其 html、body 和直接子 iframe 的宽高，并观察节点挂载。不能只以 style 节点存在判断生效，也不放宽 CSP 或访问 opaque origin 的游戏文档。顶层仅处理正式应用路径，站点加载期间由原生 loading 遮罩覆盖；布局适配报告经过官方来源、主 frame、当前应用 ID 与完整 URL 校验，再等待 WebView 视觉回调显示内容。

普通网页布局和 WebView 内连续导航规则保持；离开小程序路径后清除顶层适配标记，恢复普通网页工具栏。精确应用路径在文档完成后两秒仍没有 loader 时也恢复普通布局，避免隐藏登录、验证和不可用页；异步卸载和重新挂载 loader 同样更新布局。复制链接、浏览器打开移除内部嵌入参数，保留其他 query/hash；官网 loader 自身会从 SDK 启动参数中过滤 embed_mode。

运行时的加载、下载、解包和启动呈现由[小程序原生启动进度](2026-11-04-mini-app-native-launch-progress.md)部分替代：有安全消息接口时隐藏官网进度视觉层，通过真实阶段快照显示原生进度；权限 dialog 继续使用官网交互。本篇的 H5 运行时、稳定 WebView、几何和无 loader 回退约束继续有效。

权限交互中，已识别的剪贴板读写由[单次原生确认](2026-11-05-mini-app-native-clipboard-consent.md)部分接管；声音、视频、全屏及其他官网权限继续保留网页交互。

## Existing note audit

[小程序网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)部分重叠：本篇替代带原生返回、标题和固定整行胶囊的运行页外观；保留其 H5、关于、权限、媒体和生命周期决定。[原生应用广场](../../implemented/feature/2026-11-04-native-app-square.md)继续负责目录和返回恢复。[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)保持。未发现其他同主题 proposed/rejected 笔记。

## Testing

- 先以 MiniAppWebUiTest 复现原生标题占位、SPA 切换重建 WebView；失败记录为 `artifacts/mini-app-immersive-repro-device.txt` 与 `artifacts/mini-app-immersive-history-repro-device.txt`。相同正文调用位置修复后，9 项初步回归通过。
- 真机测得仅携带 embed 参数仍保留官网应用工具栏和约 320px 的 loader，见 `artifacts/mini-app-embed-evidence.json`。扩展探针等到官网“已就绪”才采集运行视口，发现外层 764px、内层 684px；新增 CSP 离线用例也复现样式表被阻止，见 `artifacts/mini-app-immersive-csp-repro-device.txt`。DOM 尺寸适配后两层都为 360 × 764。
- MiniAppWebUiTest 覆盖安全区域铺满、悬浮胶囊、宿主和运行 iframe、官网权限 dialog、关于与刷新、剪贴板来源隔离、SDK 全屏、后台暂停、跨页返回和同一 WebView 实例、无 loader 后恢复与重新挂载、公开链接 query/hash。历史用例先真实点击网页，避免 Chromium 把无用户手势的脚本历史标为可跳过条目。
- MiniAppInfoTest 共 4 项 JVM 用例，包括嵌入参数幂等、重复 query/hash 保留和对外链接过滤。Debug 与 AndroidTest 构建记录为 `artifacts/mini-app-immersive-final-build.txt`。
- 最终 MiniAppWebUiTest、WebScreenUiTest、WebViewMaterialLayoutUiTest 共 26 项真机回归通过（37.63 秒），包括 loading、错误重试、Cookie、外部跳转、历史恢复和六种原生主题材质组合；记录为 `artifacts/mini-app-immersive-final-device.txt`。4 项 JVM 测试通过；Lint 为 0 errors / 68 warnings / 1 hint，记录为 `artifacts/mini-app-immersive-final-lint.txt`。npm run verify-notes 验证 168 篇笔记通过，UTF-8 乱码检查通过。
- 线上 MiniAppImmersiveLiveProbeTest 经显式 `miniAppEmbedProbe=true` 启用；东北雨姐galgame 和 2048 均运行到“已就绪”，校验外层、同源宿主与隔离 iframe 的尺寸、嵌入标记、官网外壳隐藏、原生关于和关闭回来源。线上探针与历史返回用例均通过，见 `artifacts/mini-app-immersive-final-probe-device.txt`（2 tests）。对应只包含公开信息和几何数据的记录为 `artifacts/mini-app-immersive-evidence.json`；截图为 `artifacts/mini-app-immersive-0.png`、`artifacts/mini-app-immersive-1.png` 和 `artifacts/mini-app-immersive-about-0.png`。
- 测试机为 RMX2202 / Android 13，WebView 156.0.8078.47；覆盖安装前后均为 0.3.1-dev / 3002，签名 SHA-256 为 95f95aff…6623e4f。使用 adb install -r 与手动 instrument，首次安装时间保持 2026-10-23 14:55:34。

## Alternatives considered

- 仅移除原生返回和标题改动小，且不依赖官网结构，但官网导航和应用工具栏仍占空间，不能满足直接展示小程序的要求。
- 直接加载底层 iframe 或本地运行 ZIP 能完全控制首屏，但底层 iframe 依赖宿主启动、资源验证、会话和消息握手；重建这些能力超出展示适配的范围，继续保留官网运行时。
- 隐藏整个网站外壳的通用 CSS 能立即裁掉多余元素，但容易误伤登录、验证和其他页面，也不能证明小程序视口正确；优先官网嵌入模式，必要时将宿主布局适配限制在精确小程序路径，并验证首屏与页面切换。
- 分开普通网页与小程序两个脚手架结构更直观，但 Compose 会把正文当作不同调用位置并重建 WebView；共享正文位置，仅切换标题与悬浮内容。
- 同源宿主注入 CSS 能用很少规则覆盖尺寸，但官网 CSP 阻止新增样式表；改用 DOM 属性调整宿主几何，不修改其权限策略。

## Consequences

小程序可用视口不再被原生或官网工具栏挤压，仍复用完整官网运行时及已有普通材质，避免重新采样 WebView。关闭与历史导航保留页面来源和原 WebView 实例。

官网 DOM 和 CSP 仍是适配依赖；类名、挂载结构或权限弹层方式变化时应重新验收，不能推广为通用网页裁切。胶囊覆盖右上少量内容，无法保证每款第三方小程序都主动避让。内容铺满系统安全区域，系统栏仍保留；横屏、平板及其他 WebView 版本没有同等实机覆盖。

线上验收为访客身份；账号资料授权、业务网络授权和实际付费业务没有验收。离线全屏与剪贴板测试不能替代这些线上授权流程。原生视觉回调用于首屏防闪，尚无逐帧录屏的视觉证明；截图和几何通过不等于所有瞬态已覆盖。
