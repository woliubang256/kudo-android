# Agent Note: 系统通知彩色图标与单色回退

Status: rejected — 用户要求统一使用彩色自适应资源，取消厂商名单；由[统一自适应通知图标](../../implemented/bug-fix/2026-10-15-unified-adaptive-notification-icons.md)接管。

## Problem

同一台 realme RMX2202 / Android13 的状态栏和通知面板能够保留 FluxDo 的完整彩色图标，酷DO 因只传入单色 ic_notification，始终显示单色 K·。不能把 Android 通用单色规范直接当作所有 ROM 的实际行为。

## Proposal

NotificationIcons 为消息及下载进度、完成、失败统一选择 smallIcon。realme、OPPO、OnePlus 的 Oplus 系列使用 TYPE_RESOURCE 的 mipmap 自适应图标，其他及未知厂商默认保留原透明单色 K·。Android 没有公开的通知图标彩色能力查询接口，厂商范围采用保守名单；realme RMX2202 是实际验收设备，OPPO 和 OnePlus 沿用同系列策略，不能视为这两款设备已经实测。

彩色资源复用本项目 ic_kudo 图形，黄色背景与单色 monochrome 层随包分发，前景内缩16.667%抵消 AdaptiveIconDrawable 的额外绘制范围，避免 K· 被遮罩裁切。自适应资源与 Icon.createWithAdaptiveBitmap 的显示路径不同：realme 同机普通彩色矢量、普通位图、自适应位图均被染成灰块，TYPE_RESOURCE 自适应图标保留彩色；仅提供彩色像素不能保证系统彩色显示。原生 Android 的强制着色路径使用原单色资源，不能因彩色资源带有 monochrome 层就假设系统一定会自动选它。

通知权限、频道、点击目标及已读规则沿用原契约。测试使用隔离偏好、独立ID和离线夹具，结束只清理自有测试通知；生产通知不做批量重发。现有系统通知要等下次正常更新或新通知才采用新的图标选择。

## Related decisions

已检索 notification、通知图标和 ic_notification。与[本地通知投递](../../implemented/feature/2026-10-11-local-notification-delivery.md)部分重叠，仅调整图标约定，保留投递和点击契约；[运行通知](../../implemented/feature/2026-10-10-runtime-local-notifications.md)负责接收，不改变网络和生命周期。没有完全吸收或过时提案。

## Alternatives considered

- 始终单色最符合 Android 通用规范，且跨设备外观稳定；同机对照已证明目标设备能保留彩色，不能因此放弃用户确认的彩色效果。
- 直接给所有系统传入完整彩色矢量实现最少；不支持彩色的系统可能将不透明背景染成实心块，需要先比较实际渲染并明确回退边界。
- 始终使用自适应资源可减少厂商分支，并带有 monochrome 层；原生模拟器仍把彩色资源的小图标染成实心形状，不能替代显式单色回退。
- setLargeIcon 可以保留内容区彩色图片；用户要求状态栏和通知头部的小图标，增加大图标不能单独满足这个目标。

## Testing

communitySmallIconRetainsBrandColorsOnSupportedSystem 先在原实现复现实际黄色像素为0，日志 artifacts/notification-color-repro-device.txt。最初夹具遗留偏好导致 single 匹配失败，先显式清理独立测试偏好后，再得到真实的颜色断言失败；不是把夹具失败当成功复现。

只读核对 FluxDo 已安装包的 smallIcon 为 mipmap/ic_launcher 自适应资源，未设置 largeIcon；对照其当前公开 local_notification_service.dart，未复制 GPL 源码或图形进入 App。临时对比测试的跨包资源引用仅用于本机取证，最终测试移除该依赖，结果保留在 notification-color-comparison-device.txt、notification-adaptive-realme.txt 及对应 notification-compare-* 图片。NotificationIconsTest 覆盖厂商大小写/空格、Oplus名单及未知系统默认回退；NotificationIconTest 继续验证24/48/72px的透明单色轮廓、K与圆点分离，并检查消息与三种下载通知使用统一来源。

验收使用0.2.5 / versionCode 2006，显式构建参数与已安装版本一致，APK签名仍为95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f。开发版采用adb install -r覆盖，测试包采用-r -t；没有卸载或清除App数据，测试用am instrument运行。

- NotificationIconsTest的2项JVM测试通过。
- realme RMX2202 / Android13浅色下，NotificationIconTest、LocalNotificationsTest和DownloadNotificationNavigationTest共7项通过；深色下重复图标集成测试1项通过。目视确认状态栏及通知面板保留黄色Logo，消息与下载进度、完成、失败图标完整，截图为artifacts/notification-color-light-*和notification-color-dark-*。夜间模式已恢复测试前的auto。
- medium_phone原生API36模拟器使用notificationColorSupported=false，6项中5项通过，彩色像素断言按前置条件跳过1项。notification-color-stock-device.txt记录结果，notification-color-stock-message-status.png及notification-color-stock-message.png目视确认状态栏为单色K·、通知面板为系统着色的K·，没有实心色块。
- 彩色资源位于mipmap-anydpi，minSdk已是26，不再保留多余的v26目录。移动资源后刷新Gradle资源产物并重新构建，2项JVM测试通过；签名核对后重新覆盖安装，两端各重跑NotificationIconTest，realme 4项通过，原生模拟器3项通过、1项按条件跳过，日志为notification-color-final-1618704c.txt与notification-color-final-emulator-5554.txt。

## Acceptance criteria

Oplus 系统使用彩色自适应资源，其他厂商使用原透明单色轮廓。此方案仅作为被否决的兼容性备选保留，没有随正式提交生效。

## Risks

目标 realme 系统获得完整彩色品牌图标，原生与未知系统保留可读单色轮廓。厂商系统可以改变着色、大小与分组，单机通过不能推出所有系统都彩色。名单外即使支持彩色也暂时保持单色；Oplus 硬件安装第三方 ROM 后，Build.MANUFACTURER 仍可能不变，需要重访名单或增加明确的系统识别。没有公开能力接口时不以反射、降低 targetSdk 或绕过系统着色换取彩色效果。
