# Agent Note: 关于入口归拢与双品牌页头

Status: implemented

## Problem

界面设置下重复出现关于菜单和说明弹窗，与个人中心独立关于页分散；站内链接标题在窄屏换行过多。

## Decision

设置保留显示偏好、内容偏好、链接和退出登录，链接行简化为“站内链接”。关于页正文下方保留开源致谢、社区指南与隐私说明入口，并由[GitHub更新](2026-10-11-github-updates.md)增加检查更新和外部源码入口，许可与隐私沿用现有模态，指南沿用网页回调。开源致谢、社区指南与隐私说明不显示副标题。顶部两个现有品牌矢量图标与各自名称并排，中间红心；页头版本号与重复的“酷DO”版本行按用户要求移除，真实版本仍在“我的”页关于入口右侧显示。正文合并为“酷DO是 LINUX DO 的第三方安卓原生客户端”。保留底部社区 slogan。

双Logo按顶部对齐，不让中英文名称的行高影响图标位置。菜单标题与右值按可用宽度分配空间，窄屏允许说明换行，避免右侧固定最大宽度把短标题挤成逐字换行。

开源致谢精简为标题和五项项目/许可链接，移除顶部整段技术说明。LiquidModal 为居中弹窗提供可选高度上限，致谢使用可用高度的70%且最多560dp；默认仍保留现有安全区高度。内容沿用弹窗内滚动区域，“知道了”固定在底部。包内完整许可证与 THIRD_PARTY_NOTICES.md 保留。

## Existing note audit

[用户授权错误上报](2026-11-08-opt-in-sentry-reporting.md)在设置增加应用级 Sentry 开关，关于页同步必要信息收集与网络来源 IP 说明，并在致谢及包内补充 Sentry / Epitaph 许可；原有关于入口和模态布局保留。

检索关于、设置、未登录和模态。[访客账号布局](../../implemented/feature/2026-10-10-guest-account-layout.md)部分重叠，独立入口、版本和 slogan 保留；[设置模态](../../implemented/feature/2026-10-08-glass-settings-modals.md)继续提供焦点隔离、可滚动说明与退出确认。没有完全吸收或过时提案。

## Alternatives considered

- 设置页保留重复入口方便访问，但用户明确要求关于内容集中到关于页，两个入口会继续分散说明与许可。
- 将说明都铺在关于页最直接，但长许可和隐私会淹没品牌及简介，使用扁平入口与已有模态。
- 仅删除致谢说明文字改动最少，正常字号下也能缩短弹窗，但短窗口和大字号仍可能占满高度；致谢同时设置最大高度，沿用已有滚动及固定操作区。

## Testing

致谢弹窗的两项复现用例先失败于长说明未删除和短窗口超过70%高度（artifacts/credits-height-repro-device-20261011.txt）。修复后 AboutPageUiTest 六项与 SettingsModalUiTest 七项真机用例全部通过，覆盖五个链接目的地、560dp/70%高度上限、280dp宽/360dp高/130%字号下三种材质滚动，以及滚动前后“知道了”位置不变（artifacts/credits-height-fixed-device-20261011.txt）。已查看正常浅色与短窗口深色截图 credits-bounded-light.png、credits-bounded-short-dark.png。Debug/测试包构建与Lint通过，签名与手机已安装开发版一致，仅以 install -r 保留数据覆盖安装。

2026-10-11：简介更新为“酷DO是 LINUX DO 的第三方安卓原生客户端”，社区指南移除行内副标题，底部slogan保留且只有一处。AboutPageUiTest四项真机通过，覆盖新文案、指南跳转、底部标语、各材质和窄屏130%文字；日志artifacts/about-copy-device.txt。GuestProfileUiTest原“关于页无版本号”断言与已有检查更新行冲突，同批修正为仅在检查更新行显示一次版本号，返回访客页后仍显示版本；单项重跑通过，日志artifacts/about-copy-guest-fixed-device.txt。

RMX2202 / Android13 的 AboutPageUiTest 共4项全部通过（artifacts/about-layout-validation-device.txt），覆盖设置移除整组、短链接标题、双品牌与红心位置、四项入口及许可链接、暗色280dp宽130%字体与各材质模态、菜单短标题完整。窄屏标题先复现为3行，再修正到单行（artifacts/about-row-details-diagnosis.txt）。已查看 about-two-brands.png、about-dark-narrow-liquid.png 与 about-dark-narrow-liquid-footer.png，正文、菜单和底部 slogan 可通过滚动访问。

窄屏测试通过 LazyColumn 的 performScrollToNode 定位跨屏条目，避免沿用离屏预取节点的旧坐标；切换材质后仍能从底部返回双品牌页头，不放宽可见性断言。SettingsModalUiTest 的7项通过（artifacts/content-about-fixed-device.txt），说明弹窗用例迁移到关于页，显示设置和退出确认仍验证原入口；GuestProfileUiTest 的4项通过（artifacts/content-about-device.txt），独立关于正文断言已更新。Debug与测试APK构建成功，Lint为0错误、30项既有警告和1项提示。

页面精简后重新构建并覆盖安装，AboutPageUiTest四项和GuestProfileUiTest的关于返回用例共5项通过（artifacts/about-simplify-device.txt），确认页头版本、版本行和两个副标题消失，许可/隐私弹窗、指南链接、底部slogan、窄屏大字以及“我的”页版本号保留。已查看artifacts/about-simplified.png，三个菜单入口与右侧箭头对齐。

## Consequences

设置里的关于入口移除后需要从“我的”进入，用户仍可返回原账号页。品牌名称按官方 LINUX DO 拼写统一。

致谢在小屏或大字号下需要滚动查看全部项目，底部确认按钮始终可见；其他居中弹窗使用默认高度约束。
