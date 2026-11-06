# Agent Note: 外部链接默认在应用内打开

Status: implemented

## Problem

用户要求外部链接默认在应用内打开。当前 DisplaySettings、偏好缺失／未知值、WebScreen 与启动器的省略参数均使用 SYSTEM，首次使用普通外链会离开应用。更改默认值还必须保留已保存的 SYSTEM 或 IN_APP 选择。

## Decision

DisplaySettings、AppSettings 缺失／未知值回退、WebScreen 与 ExternalAppLinkLauncher 的默认模式统一为 IN_APP。KudoApp 给网页的系统启动回调显式传入当前偏好，避免在用户选择 SYSTEM 后又被启动器的新省略参数挡回网页。既有设置键和枚举取值不变，不做覆盖式迁移，已落盘选择照常读取；显式“在浏览器打开”仍使用独立动作。

## Existing note audit

已检索活跃笔记中的外链、打开方式和默认值。[链接打开方式](../../implemented/feature/2026-10-14-external-link-opening-mode.md)部分被本篇取代：仅默认 SYSTEM 改为 IN_APP，保留用户选择、域名边界、主动点击、启动失败回退和显式浏览器规则。[应用内网页](../../implemented/feature/2026-10-10-in-app-web-links.md)部分重叠，继续管理网页历史、Cookie 与原生入口分流。[夸克包名例外](../../implemented/bug-fix/2026-10-16-quark-share-package-routing.md)仅在 SYSTEM 生效，不改变规则。没有同主题 proposed／rejected；旧笔记仍持有独立决定，不归档。

## Alternatives considered

- 只修改偏好读取的回退值能减少代码改动，但 DisplaySettings 与独立网页／启动器调用仍会有另一套默认；统一省略参数，并给需要系统行为的调用明确传入模式。
- 把旧 SYSTEM 记录一并迁移到 IN_APP 能让所有存量用户立即采用新默认，但现有格式无法区分用户主动选择与旧默认落盘，会覆盖用户偏好；仅修改缺失和无效配置。

## Testing

先只安装新增测试 APK，对旧运行时代码运行 3 项复现测试，默认显示配置／启动器、缺失与未知存储值、WebScreen 省略参数场景全部按预期失败，证据见 `artifacts/external-link-default-repro-device.txt`。

修复后 16 项 JVM 专项测试通过（ExternalAppLinkPolicyTest 5、LinuxDoLinkTest 11）；Debug、AndroidTest 构建及 Lint 通过，0 errors、71 warnings、1 hint，见 `artifacts/external-link-default-fixed-build.txt`。

RMX2202 / Android 13 上 25 项真机测试全部通过（WebScreenUiTest 15、ExternalAppLinkLauncherTest 7、ExternalLinkSettingsUiTest 3），见 `artifacts/external-link-default-fixed-device.txt`。覆盖默认应用内浏览与同实例历史、显式浏览器动作、两种有效偏好重建及更新其他设置后的保留、未知配置回退、SYSTEM 主动点击分流、域名边界、异常回退及脚本／重定向隔离。系统分流通过捕获 Intent 验证，不把它记为第三方应用端到端验收。

构建显式使用 versionName=0.3.5-dev、versionCode=3006，与已安装开发版一致；安装前核对签名 SHA256 为 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f。应用使用 adb install -r、测试 APK 使用 -r -t，随后 am instrument；设置测试使用隔离 SharedPreferences，不触碰真实偏好和登录态。已查看 `artifacts/external-link-default-settings.png`，确认“应用内打开”黄色选中态及中文可读。

## Consequences

没有有效偏好的用户直接在应用内浏览外链，各入口的省略参数保持一致。以前更新任何显示设置都可能写入 SYSTEM；这些记录无法识别是否主动选择，因此按已保存偏好保留，用户可在设置中切换。应用内网页与系统浏览器登录态不同，沿用既有隔离，不复制会话。
