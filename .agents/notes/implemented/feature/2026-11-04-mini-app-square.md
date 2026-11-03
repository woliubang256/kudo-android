# Agent Note: 应用广场与小程序网页容器

Status: implemented

## Problem

社区新增 /apps 应用广场。现有网页工具栏分散展示刷新、浏览器和关闭，缺少小程序常驻胶囊、应用信息与全屏适配。用户要求在我的页面设置上方提供入口，并保留官网 H5 运行方式。

## Decision

访客和登录用户均在设置上方显示无副标题的应用广场入口。官网 /apps 及其应用路径使用普通材质的固定胶囊，更多包括刷新、复制链接、浏览器打开和关于；关于展示官网实际提供的图标、名称、简介、版本和发布人，读取失败可重试，不虚构字段。关闭退出当前网页容器，返回优先关闭弹层、退出全屏、回退网页历史。

目录入口的 H5 决策由[原生应用广场](2026-11-04-native-app-square.md)部分替代；本篇继续约束单个小程序运行页，以及已在 WebView 中进入 /apps 的网页连续浏览。

胶囊可见高度为 32dp，更多与关闭各保留 48dp 触控区，按压反馈限制在 32dp 可见表面内。图标使用用户提供的原始 SVG 路径：更多为中大侧小三点，关闭为圆环加实心点，颜色跟随主题。MiniAppWebUiTest 同时验证胶囊视觉高度与两枚按钮的触控尺寸，微调后的 7 项运行页回归通过，记录在 `artifacts/native-apps-refined-device.txt`；实机胶囊见 `artifacts/mini-app-capsule-dark.png`。

加载官网宿主页及其隔离 iframe，继续复用 CookieManager、JavaScript、DOM 存储及默认第三方 Cookie 限制。不下载运行用户提供的开发 ZIP，不复制平台 SDK、鉴权或审核权限；文档仅作为协议参考。全屏保留原生胶囊，返回先退出弹层再退出全屏；切到后台暂停可访问的媒体、隐藏网页并取消剪贴板确认，关闭释放 WebView 和原生桥。保留 HTTPS、CORS 与平台资源校验。

关于通过官网会话在页面内请求 `/mini-app-api/public/<app-id>/info`，只读取 `app` 的公开字段，不在原生层复制发布配置或令牌。响应必须与当前请求 ID 对应，导航取消在途请求。缺失的可选字段省略、图标失败显示通用应用图标；网络错误显示重试，不猜测版本或发布人。

剪贴板保留标准浏览器接口，缺失方法或遇到 `NotAllowedError` / `NotSupportedError` 时才回退到需本次确认的原生文字读写，限制 4 KiB。WebView 156 暴露 readText 但实际返回 NotAllowedError，单纯检查方法是否存在不足以适配。消息接口使用 AndroidX WebKit 的来源过滤，仅向 `https://linux.do` 注入；接收还检查当前顶层为正式应用广场或精确应用路径、来源为官网且 App 在前台。第三方与 opaque origin 沙箱 iframe 没有消息对象，平台隔离与权限检查仍由官网宿主完成。DocumentStart 注入兼容脚本，并在站内 history 变化与加载完成时幂等补装，覆盖 Ember 从普通页面进入应用的情况。

不放开文件访问、content URI、混合内容、第三方 Cookie 或无手势自动媒体播放；SDK 的存储、用户、主题、包验签、固定 CDN 素材、HTTP/WS 和鉴权仍走官网实现。WebView 不支持安全消息接口时不退回无来源约束的 addJavascriptInterface；关于显示加载失败，其他网页能力继续使用浏览器自身支持。

## Existing note audit

[应用内网页](../../implemented/feature/2026-10-10-in-app-web-links.md)部分重叠：复用官网会话、连续历史和受限网页容器，仅对小程序增加工具栏与能力；[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)保留，胶囊和弹窗均不采样 WebView。外部链接打开方式保持既有策略。未发现同主题 proposed 或 rejected 笔记。

## Alternatives considered

- 直接运行 ZIP 能完全控制小程序界面，但必须重建官网 SDK、沙箱和鉴权，会偏离复用 H5 的要求；继续使用官网运行时。
- 使用通用 JavaScript 原生桥实现全部能力最省接入判断，但会扩大外站和嵌入内容的访问面；优先使用 WebView 标准能力，缺口按官网来源与用户操作单独适配。
- 全局打开第三方 Cookie、文件访问或自动媒体播放能容忍部分不规范网页，但文档并不要求这些权限，且破坏已有网页边界；保留现有限制。

## Testing

- MiniAppInfoTest 验证官网 HTTPS 路径、query/hash 保留、服务器字段、错误身份及不安全图标过滤。
- AppSquareEntryUiTest 验证访客、登录和退出后的入口都在设置上方且无登录拦截。MiniAppWebUiTest 使用真实 WebView 与离线官网响应，覆盖关于字段、跨应用切换、失败重试、真正全屏和返回顺序、关闭释放、剪贴板确认及来源隔离、六种主题材质组合与 history 补装、后台暂停。
- 修复前分别出现剪贴板方法存在却返回 NotAllowedError、默认图标缺失无障碍描述、history 进入应用未安装兼容脚本的失败，证据为 `artifacts/mini-app-current-device.txt`、`artifacts/mini-app-clipboard-repro-device.txt` 和 `artifacts/mini-app-history-repro-device.txt`。这些是新增功能的测试缺口，不是此前用户系统闪退的复现。
- 设备为 RMX2202 / Android 13，WebView 为 com.google.android.webview 156.0.8078.47。已安装开发版与构建均为 0.3.1-dev / 3002，覆盖前证书 SHA-256 一致（95f95aff…6623e4f），使用 adb install -r、adb install -r -t 与 am instrument；不卸载、不清空登录数据。
- MiniAppLiveProbeTest 仅在显式 miniAppLiveProbe=true 时访问官网广场、2048 与能力测试，检查官网运行至“已就绪”并展示原生关于，不评分、不举报、不发布内容。启动会受官网正常使用量统计规则影响；登录权限与收费、外部业务服务不属于该探针。
- 最终三项 JVM 与 23 项相关真机回归全部通过（34.533 秒）；Debug / AndroidTest 构建通过，Lint 为 0 errors / 66 warnings / 1 hint，npm run verify-notes 通过。日志为 `artifacts/mini-app-final-build.txt`、`artifacts/mini-app-final-device.txt` 与 `artifacts/mini-app-final-lint.txt`。最终的额外变动仅为清理 WebScreen 行尾和冗余同包 import、为线上探针保留失败截图与页面诊断；再次构建通过。
- 桌面浏览器已核对官网实际公开接口、应用图标和发布人字段；手机两次线上探针都在广场主文档阶段超时，诊断页为 `chrome-error://chromewebdata/` / `net::ERR_CONNECTION_TIMED_OUT`，没有运行到 2048 或能力测试。失败详情及截图为 `artifacts/mini-app-live-diagnostic-device.txt`、`artifacts/mini-app-live-failure.json` 与 `artifacts/mini-app-live-square-failure.png`，不把线上手机验收写成通过。
- 额外 GuestProfileUiTest 中三项入口／布局回归通过，`aboutShowsVersionInUpdateRowAndBackReturnsToGuest` 的“检查更新 + 当前版本”显示断言失败。本次没有改动 AboutScreen，也未把这一项计为通过；输出见 `artifacts/mini-app-fixed-device.txt`。最终相关的 23 项不包含这项额外关于酷DO页面检查。

## Consequences

官网接口或 DOM 结构可能变化；提供明确错误而不从地址猜字段。SDK 存储按应用及正式/沙箱隔离，不自动按账号切换，不宣称分用户。原生剪贴板回退可能在官网权限确认后再次确认，这是浏览器能力缺失时的本次授权边界。固定胶囊会占用部分屏幕，但全屏后仍保留退出入口。网页及弹层统一普通材质，不重新引入采样 WebView 的闪退风险；其他原生页面仍遵循全局设置。

测试机和当前 WebView 的通过不能代替其他系统版本的兼容验收。沙箱开发路由不在正式应用胶囊与公开关于接口的识别范围内，继续按普通网页浏览；缺少 DOCUMENT_START_SCRIPT 时只能在顶层页面补装，嵌入 frame-host 的兼容能力仍取决于内核。真实登录后的剪贴板、账号资料和业务网络授权仍需账号验收，不以匿名官网加载或离线夹具的成功替代。
