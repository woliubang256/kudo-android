# Agent Note: 第三方分享链接优先打开对应 App

Status: implemented

## Problem

夸克网盘等分享链接从原生正文进入 WebView。Android 13 真机已安装夸克，但未指定包名的分享 HTTPS Intent 只匹配 Chrome；夸克声明了通用 HTTP(S) 浏览器过滤器，不能用通用 App Links 匹配完成此需求。

## Decision

原生内容保持文件下载和 Linux.do 导航优先。第三方 HTTP(S) 链接查询匹配 Activity，排除通用浏览器、本应用和系统解析器；一个候选直接打开，多个候选仅在候选 App 中选择，没有可用候选或启动失败回退应用内网页。准确的 pan.quark.cn/s/<id> 分享地址例外，向 com.quark.browser 发送原始 URL，保留提取码和 fragment，不启动 App 首页。显式“在浏览器打开”保持原来的含义。

跳转器与原生正文仅接受夸克发布的 qklink/uclink www.uc.cn Android appkey 的 open_url 分享协议；任意 intent、命令、文件或其他 scheme 不开放。未安装时使用协议中经过同样校验的分享网页回退。网页暂时维持原有行为。第三方 App 使用自己的网络配置，不继承本应用 DoH/ECH。

候选 App 使用 GET_RESOLVED_FILTER 查询匹配域名过滤器，并排除能处理通用探测网页的浏览器包。ResolveInfo 的 handleAllWebDataURI 字段不属于公开 Android SDK，不能依赖它。Android 11+ 包可见性声明 HTTP(S) 查询以及夸克包，不申请 QUERY_ALL_PACKAGES。

## Existing note audit

[网页浏览](../../implemented/feature/2026-10-10-in-app-web-links.md)部分重叠，保留站内网页连续历史、Cookie 与显式浏览器入口，本篇接管第三方 App 分流例外。[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)保留系统入口与原生解析。[下载管理](../../implemented/feature/2026-10-14-native-download-manager.md)保留文件拦截优先级。没有完全吸收或过时提案。

## Alternatives considered

- 直接发送隐式 ACTION_VIEW 最接近 Android 默认行为，但真机夸克分享只匹配 Chrome，也可能让普通网页离开应用；过滤浏览器并增加精确的夸克分享规则。
- 给所有夸克域名指定夸克能覆盖更多网页，但官网等普通页面不等于分享任务；只处理准确的分享路径。
- 任意 intent:// 原样执行能兼容网页唤端参数，但会接受页面提供的 component、extras、flags 和其他命令；只接收校验后的 URL，重建受控 VIEW Intent。

## Testing

RichContentLinkUiTest 两项真机用例通过，正文中的受支持夸克分享协议进入统一回调，任意 quark 命令仍为普通文字；已有站内/外站文字链接与回调重组行为通过。日志 artifacts/app-link-content-build.txt、app-link-content-device.txt。

13 项 JVM 用例通过，覆盖准确域名/分享路径、提取码保留、协议限制、浏览器排除与站内解析。RMX2202 / Android 13 的 11 项真机用例通过，覆盖已安装匹配 App、多个候选、未安装/启动失败返回网页分支，以及原生站内导航；真实包可见性找到 com.quark.browser，并捕获生产跳转器发送的原始分享 URL。日志 artifacts/app-link-policy-build.txt、app-link-native-build.txt、app-link-native-device.txt。构建显式 2005 / 0.2.3，与已安装版本相同，安装前回读签名 SHA256 与构建一致，以 adb install -r 覆盖保留数据。

## Consequences

第三方 App 版本决定 URL 是否进入原生网盘或其内部分享网页，无法保证每个分享有效。域名关联和厂商系统设置可能隐藏候选，回退网页。夸克协议来自当前公开分享页脚本及真机 intent filter，其他命令和任意 App scheme 不在支持范围。
