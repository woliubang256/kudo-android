# Agent Note: 第三方分享链接优先打开对应 App

Status: implemented

## Problem

夸克网盘等分享链接从原生正文进入 WebView。Android 13 真机已安装夸克，但未指定包名的分享 HTTPS Intent 只匹配 Chrome；夸克声明了通用 HTTP(S) 浏览器过滤器，不能用通用 App Links 匹配完成此需求。

## Decision

[外部链接打开方式](2026-10-14-external-link-opening-mode.md)取代本篇启动器筛选候选与指定夸克包的决定：系统模式直接发送隐式 VIEW，应用内模式不唤端。下述旧选择理由保留，协议校验、网页手势边界和历史验证限制仍有效。

原生内容保持文件下载和 Linux.do 导航优先。第三方 HTTP(S) 链接查询匹配 Activity，排除通用浏览器、本应用和系统解析器；一个候选直接打开，多个候选仅在候选 App 中选择，没有可用候选或启动失败回退应用内网页。准确的 pan.quark.cn/s/<id> 分享地址例外，向 com.quark.browser 发送原始 URL，保留提取码和 fragment，不启动 App 首页。显式“在浏览器打开”保持原来的含义。

跳转器与原生正文仅接受夸克发布的 qklink/uclink www.uc.cn Android appkey 的 open_url 分享协议；任意 intent、命令、文件或其他 scheme 不开放。未安装时使用协议中经过同样校验的分享网页回退。第三方 App 使用自己的网络配置，不继承本应用 DoH/ECH。

WebView 仅在主文档、带用户手势、非重定向的第三方请求上尝试 App；Linux.do 站内导航、新窗口与重定向继续维护网页历史。HTTP(S) 未匹配 App 时正常网页导航，受支持的分享 scheme 在主动点击但无法启动 App 时加载其分享网页；脚本或重定向请求的 scheme 不唤端，也不自动重载分享页，避免唤端脚本循环。回调通过 rememberUpdatedState 跟随重组。显式浏览器动作保持独立。

候选 App 使用 GET_RESOLVED_FILTER 查询匹配域名过滤器，并排除能处理通用探测网页的浏览器包。ResolveInfo 的 handleAllWebDataURI 字段不属于公开 Android SDK，不能依赖它。Android 11+ 包可见性声明 HTTP(S) 查询以及夸克包，不申请 QUERY_ALL_PACKAGES。

## Existing note audit

[网页浏览](../../implemented/feature/2026-10-10-in-app-web-links.md)部分重叠，保留站内网页连续历史、Cookie 与显式浏览器入口，本篇接管第三方 App 分流例外。[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)保留系统入口与原生解析。[下载管理](../../implemented/feature/2026-10-14-native-download-manager.md)保留文件拦截优先级。没有完全吸收或过时提案。

## Alternatives considered

- 直接发送隐式 ACTION_VIEW 最接近 Android 默认行为，但真机夸克分享只匹配 Chrome，也可能让普通网页离开应用；过滤浏览器并增加精确的夸克分享规则。
- 给所有夸克域名指定夸克能覆盖更多网页，但官网等普通页面不等于分享任务；只处理准确的分享路径。
- 任意 intent:// 原样执行能兼容网页唤端参数，但会接受页面提供的 component、extras、flags 和其他命令；只接收校验后的 URL，重建受控 VIEW Intent。

## Testing

最终 Debug 构建和 Lint 通过，0 errors、45 条原有 warnings、1 hint，没有新增告警。最终包仍为 2005 / 0.2.3，签名与安装前回读一致，再次以 adb install -r 覆盖，产物 artifacts/kudo-app-links.apk；104 篇笔记校验通过。日志 artifacts/app-link-final-build.txt、app-link-final-notes.txt。

WebScreenUiTest 的 12 项及 DownloadNotificationNavigationTest 的 1 项真机用例通过，覆盖真实触摸唤端后来源网页不变、HTTP(S)/分享 scheme 未安装回退、真实脚本导航不唤端、主文档/手势/重定向边界，以及既有历史、Cookie、加载、主题和通知入口。日志 artifacts/app-link-web-final-device.txt。首轮分享 scheme 夹具触摸误点相邻的 redirect 链接；记录生产请求 URL/gesture/redirect 后确认误点，测试页面分开触摸区域后通过，未为此改变产品手势策略。失败日志 artifacts/app-link-web-device.txt、app-link-web-diagnostic-device.txt 的早期运行已被后续诊断覆盖，首轮失败日志保留。

实际外部唤起验收：自动审批拒绝 adb ACTION_VIEW 打开 com.quark.browser，返回“blocked by policy”，未给出更具体原因；没有实际打开第三方 App 或截图。包可见性及生产跳转器发出的 URL/包名已验证，不能替代第三方 App 分享页面端到端验收。当前用的是无效分享夹具，未读取真实网盘文件，也未验证第三方 App 原生保存操作。

RichContentLinkUiTest 两项真机用例通过，正文中的受支持夸克分享协议进入统一回调，任意 quark 命令仍为普通文字；已有站内/外站文字链接与回调重组行为通过。日志 artifacts/app-link-content-build.txt、app-link-content-device.txt。

12 项 JVM 用例通过，覆盖准确域名/分享路径、提取码保留、协议限制、浏览器排除与站内解析。RMX2202 / Android 13 的 11 项真机用例通过，覆盖已安装匹配 App、多个候选、未安装/启动失败返回网页分支，以及原生站内导航；真实包可见性找到 com.quark.browser，并捕获生产跳转器发送的原始分享 URL。日志 artifacts/app-link-policy-build.txt、app-link-native-build.txt、app-link-native-device.txt。构建显式 2005 / 0.2.3，与已安装版本相同，安装前回读签名 SHA256 与构建一致，以 adb install -r 覆盖保留数据。最终 APK 上又通过同一跳转器的 7 项用例，日志 artifacts/app-link-final-launcher-device.txt；重复运行不另计用例数，本轮共 26 项不同真机用例。

## System matching audit

用户质疑是否必须为其他 App 逐个写包名后，再次只读核查 RMX2202 / Android 13（`artifacts/app-link-system-audit.txt`）：未指定包的夸克分享 HTTP 与 HTTPS 均只查询到 Chrome；指定 com.quark.browser 能匹配 MainActivity；qklink 的 www.uc.cn / Android appkey 路径无需包名也能查询到夸克。后者仅验证系统匹配，不等于实际分享页面唤起验收。

默认浏览器角色是 com.android.chrome。`pm get-app-links --user 0` 中夸克没有域名记录；本 App 的 linux.do 自动验证状态为 1024（并非 verified），但用户选择的 Enabled 包含 linux.do。无包名的 linux.do VIEW 查询确实能找到本 App、另一个已安装论坛 App 与 Chrome。因此接收端声明和系统域名打开设置能建立匹配，发送端无需预先知道本 App 包名；仅增加 HTTP(S) scheme 或 queries 不能让未建立域名关联的夸克接管网盘链接。

当前其他 HTTP(S) App 使用通用匹配而非包名清单；夸克包名是其浏览器角色下的精确分享兜底。多个候选的面板由 Intent.createChooser 请求系统提供，厂商、系统版本和默认打开设置会影响外观及是否显示；没有读取一加 13，不能把两台手机的差别归因到某个具体设置。本次调查不改变运行时策略，也不尝试绕过此前受拒的外部启动操作。

## Consequences

第三方 App 版本决定 URL 是否进入原生网盘或其内部分享网页，无法保证每个分享有效。域名关联和厂商系统设置可能隐藏候选，回退网页。夸克协议来自当前公开分享页脚本及真机 intent filter，其他命令和任意 App scheme 不在支持范围。
