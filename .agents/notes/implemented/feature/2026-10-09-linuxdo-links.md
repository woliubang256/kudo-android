# Agent Note: Linux.do 深层链接路由

Status: implemented

## Problem

系统入口仅接收 /t/ 链接，用户主页及其他站内地址无法进入应用。同一个 URL 再次唤起不会触发 Compose effect，恢复 Activity 时又可能重复导航。扩大入口后显式浏览器动作可能被应用自身接回。

## Decision

[外部链接打开方式](2026-10-14-external-link-opening-mode.md)以 linux.do 及其真实子域名为应用内边界；本篇原生解析仍仅用于 linux.do 主域，子域名进入网页，不扩大系统深链 Manifest 域名。

应用内未知页面与外站的回退由[应用内网页](2026-10-10-in-app-web-links.md)部分替代；本篇保留系统入口消费和原生目标格式。

同源 HTTP(S) 链接解析为帖子、用户、分类、首页四种目标；解析与 Android 导航分离。帖子保留 /t/id 兼容，并支持 /t/slug/id/number；用户路径忽略用户名后的子路径；分类支持 /c/id、/c/slug/id 和嵌套 slug 后的有效 ID。每次新 Intent 作为独立事件消费，重建后不重放已消费地址。其他系统传入的站内地址清回首页并选中首页页签，非站内地址不参与路由。显式浏览器动作使用浏览器包名避免自身回环。

正文普通文字链接与 onebox 共用传入的 onLink 回调。Compose 的 LinkAnnotation.Url 默认使用 LocalUriHandler，RichContent 在正文范围提供自己的 UriHandler，避免文字链接绕过应用路由；回调重组时随之更新。文本仍保留选择、链接语义和样式，显式“浏览器打开”继续使用独立浏览器动作。

## Existing note audit

[原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)、[数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)和[详情作者](../../implemented/feature/2026-10-09-topic-author-and-responses.md)部分重叠，分别保留导航结构、网络同源和深链主帖补齐的职责。本篇不替代上述决策。检索未找到独立深链决策或过时提案。

## Alternatives considered

- 将所有 URL 直接注册为 Navigation deepLink：可少写入口代码，但用户子路径及任意站内地址的首页回退仍需统一解析，且需要独立处理重复 Intent，故使用显式目标解析。
- 按 URL 文本去重：能阻止恢复时重复入栈，但无法区别恢复与用户再次打开同一地址，改为事件消费和恢复标记。
- 继续让文字链接使用系统 UriHandler：无需自定义处理，但未关联域名时打开浏览器，且与 onebox 行为不一致。正文范围提供 UriHandler，复用现有链接分流并保留 Compose 链接交互。

## Verification

分类链接先由 LinuxDoLinkTest 新用例复现错误回到 Home，日志 artifacts/category-link-repro.txt；修复后精确断言分类 ID，DeepLinkUiTest 同时更新为分类入栈及返回原话题。新增分类改动随应用内网页20项集中真机回归全部通过，日志 artifacts/in-app-web-final-device.txt。

LinuxDoLinkTest 的4项测试通过，覆盖 slug、楼层、用户子路径、无效 ID、查询参数及非本站来源。DeepLinkUiTest 的2项真机用例直接调用生产 IncomingLinuxDoLink 与导航 helper，验证初次消费、返回后同址再开、用户子路径及首页页签回退；它们不声称验证 Activity Intent 生命周期。第8楼滚动由 TopicPresentationUiTest 的实际详情列表验证，截图 presentation-floor-eight 已查看。

现场点击正文文字链接时复现绕过 onLink。RichContentLinkUiTest 首先失败（expected URL、actual 空列表），日志 artifacts/presentation-link-repro-tests.txt；提供正文 UriHandler 后通过，同时验证回调重组和外站地址仍交给应用分流。修复后详情、导航及文字链接8项回归全部通过（14.544秒），日志 artifacts/presentation-link-fixed-tests.txt。结合先前46项回归，共47个不同UI用例通过；106项JVM、构建和Lint通过。

真实正文 URL 点击已进入应用详情，目标2974886返回服务器404，未将内容不可用误记为路由失败；截图 presentation-live-native-link-fixed。显式“浏览器打开”进入Chrome，截图 presentation-live-browser。设置快捷入口打开本应用的系统默认打开页，新增链接列表准确显示linux.do，截图 presentation-live-supported-links 与 presentation-live-domain-choice；未更改设备域名关联。

自动审批拒绝 adb 外部 VIEW 启动（blocked by policy，未给出更具体原因），因此外部冷/热启动和旋转重建未现场验证。原 MainActivity ActivityScenario 用例有生命周期等待问题，改为共享生产处理器的确定性用例，没有将其当作真实外部唤起的替代证据。

分发 artifacts/kudo-topic-presentation.apk 与构建、真机回读 APK 的 SHA256 一致：6e55dc4cac40ee92076619d8568a906894b86e7ee802cb9b92b43d1daeabb10d。保留原安装签名覆盖安装，未清理数据。

## Consequences

Android 12 及以上对未验证的域名需要用户在系统“默认打开”中启用；应用不能自行发布 linux.do 的 assetlinks.json。深链目标内容仍受服务器权限和网络验证约束。
