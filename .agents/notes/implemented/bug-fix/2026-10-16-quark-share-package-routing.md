# Agent Note: 夸克分享链接指定已安装应用

Status: implemented

## Problem

系统模式下的普通第三方链接使用隐式 `ACTION_VIEW`。Android 13 真机上，夸克已安装但 `https://pan.quark.cn/s/<id>` 分享链接只匹配 Chrome，回复里的夸克链接因此离开应用后没有进入夸克。

## Decision

Manifest 的 `<queries>` 声明准确的夸克包 `com.quark.browser`，允许系统查询该处理程序。启动器只对通过现有 `quarkShare` 校验的 HTTPS `https://pan.quark.cn/s/<id>` 链接设置 `Intent.setPackage("com.quark.browser")`，保留原始提取码和 fragment；普通外站、Linux.do 和受控 `qklink`/`uclink` 协议继续使用当前隐式 Intent。夸克未安装或启动失败时返回 false，由既有应用内网页回退处理。

这是一条对[链接打开方式](../feature/2026-10-14-external-link-opening-mode.md)“系统模式不指定包名”决定的窄范围例外，不改变普通外站的系统模式或应用内模式。[第三方分享链接决策](../feature/2026-10-14-external-app-links.md)中的真机匹配证据继续作为包名选择依据。

## Existing note audit

已检索 `proposed`、`implemented` 和 `rejected` 中的夸克、包名与外部链接记录。旧的第三方分享决策与链接打开方式部分重叠但各自保留：前者提供包名和系统匹配证据，后者继续约束默认链接模式；本篇只接管 HTTPS 夸克分享的显式目标。没有完全吸收或过时提案。

## Alternatives considered

- 继续发送隐式 HTTPS Intent 最符合系统默认行为，但已在目标真机复现只打开 Chrome，不能解决当前链接。
- 给所有 `pan.quark.cn` URL 指定夸克能覆盖官网和非分享页面，但会把普通网页错误地当作网盘任务；只匹配准确分享路径。
- 把夸克加入本应用的 intent-filter 只能改变接收端，不能让夸克接管发送给它的 URL；包名目标必须由运行时启动器设置。

## Testing

先在真机上运行复现用例，确认旧实现的夸克分享 Intent package 为 `null`。修复后 `ExternalAppLinkPolicyTest` 通过；`ExternalAppLinkLauncherTest` 的6项真机用例通过，覆盖显式夸克目标、普通外站和 qklink 保持隐式、应用内模式、站内边界、启动失败和不安全 scheme。主 APK 与测试 APK 使用 `versionCode=2011`、`versionName=0.2.6-dev` 构建，主 APK 以 `adb install -r`、测试 APK 以 `adb install -r -t` 覆盖，未卸载或清除数据。策略测试与 AndroidTest 构建通过。

## Consequences

当前真机安装夸克时，准确的 HTTPS 分享链接能直接交给 `com.quark.browser`，普通第三方链接仍由系统选择处理程序，夸克未安装或启动异常仍回退应用内网页。夸克版本或厂商包名变化可能使显式启动失败；回退路径保留。Intent 目标和启动器回退已验证，未把自动测试当作真实夸克账号内分享页和文件操作的端到端验收。
