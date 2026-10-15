# Agent Note: 统一使用彩色自适应通知图标

Status: implemented

## Problem

同一台realme RMX2202 / Android13上，FluxDo的状态栏和通知面板均显示彩色图标，酷DO只显示单色。只传入透明单色资源会丢失系统原本支持的彩色效果，按厂商限制也会让其他支持彩色的系统无法使用该资源。用户明确要求统一传入彩色自适应图标。

## Decision

消息通知和下载进度、完成、失败通知直接使用R.mipmap.ic_notification_color，不根据Build.MANUFACTURER分流，不增加厂商选择器。系统最终着色与遮罩由SystemUI决定。

资源采用TYPE_RESOURCE自适应图标，复用本项目ic_kudo，黄色背景，前景内缩16.667%抵消AdaptiveIconDrawable的额外绘制范围；monochrome层引用现有透明单色ic_notification。minSdk为26，资源放在mipmap-anydpi。保留monochrome资源并不保证通知栏一定采用该层。

## Related decisions

已检索通知图标、notification与ic_notification。[按厂商回退方案](../../rejected/bug-fix/2026-10-15-notification-color-icons.md)被本方案完全取代，保留实测差异以免再次假设所有系统表现相同；[本地通知投递](../../implemented/feature/2026-10-11-local-notification-delivery.md)部分重叠，本篇只接管图标选择，权限、频道、点击及已读契约继续保留；[运行通知](../../implemented/feature/2026-10-10-runtime-local-notifications.md)接收与生命周期不变。无其他被吸收或过时提案。

## Alternatives considered

- 按Oplus厂商名单选择彩色、其余单色能保住原生系统的K·轮廓，且两条路径已有实测；用户要求取消厂商限制，厂商名也不能精确代表ROM能力，因此不采用。
- 始终单色跨系统着色最稳定；realme同机已证实支持完整彩色，继续单色无法满足目标。
- 普通彩色矢量、位图或Icon.createWithAdaptiveBitmap实现直接；realme实测均被染成灰块，资源型adaptive-icon才能保留彩色。
- setLargeIcon能在内容区保留彩色图片；不能单独满足状态栏和通知头部小图标的目标。

## Testing

- 四种通知均传入同一彩色自适应资源，代码不包含厂商判断。
- NotificationIconTest独立断言四种通知的资源，并验证消息smallIcon加载为AdaptiveIconDrawable且含品牌黄色。单色源资源的24/48/72px轮廓测试继续覆盖monochrome层。
- realme及原生模拟器执行同一套断言，无厂商参数或条件跳过；截图区分资源像素验证与SystemUI实际着色。
- 仅覆盖安装已签名开发版，保留用户数据；不批量重发原有通知。

先在原实现复现黄色像素为0（notification-color-repro-device.txt），再在原生模拟器对厂商名单原型执行统一断言，得到资源不一致和非AdaptiveIconDrawable两个失败（notification-color-unified-repro-device.txt）。去掉名单后两项均通过。测试前后清理隔离偏好，避免把夹具遗留状态造成的single失败误当成图标问题。

原生模拟器随后暴露一次notify后立即读取activeNotifications的时序失败（notification-unified-timing-repro-device.txt）。测试改为最多等待5秒确认系统已投递，完成/失败还分别确认通知正文已更新，不把旧进度通知当作新结果；生产通知逻辑没有为测试增加等待。

- realme RMX2202 / Android13及medium_phone原生API36模拟器上，NotificationIconTest、LocalNotificationsTest、DownloadNotificationNavigationTest各7项全部通过，没有条件跳过；日志为artifacts/notification-unified-1618704c.txt和notification-unified-emulator-5554.txt。
- realme深色下追加图标集成测试1项通过，日志notification-unified-realme-dark.txt，夜间模式恢复原值auto。图标资源与原型相同，浅色截图notification-color-light-*和最终深色截图notification-unified-realme-dark-*均确认完整黄色K·。
- 最终截图notification-unified-1618704c-*目视确认状态栏与面板保留彩色；notification-unified-emulator-5554-*确认原生系统状态栏变成白色实心圆、面板变成系统着色圆环。截图是实际系统呈现，资源像素测试通过不等于系统最终保留彩色。
- assembleDebug、assembleDebugAndroidTest及lintDebug通过；Lint为0 errors、46 warnings、1 hint，与任务前基线一致，没有新增警告。日志notification-color-unified-final-build.txt、notification-color-unified-test-build.txt及notification-color-unified-final-lint.txt。
- 构建显式传入versionName=0.2.5、versionCode=2006，均不低于设备已安装版本；APK签名与已安装开发版一致（SHA-256: 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f），App使用adb install -r，测试包使用-r -t，手动am instrument运行，没有卸载或清除用户数据。

## Consequences

所有系统收到相同的品牌自适应资源，支持彩色的ROM无需加入名单即可显示。realme实测保留彩色；原生API36模拟器会把状态栏小图标染成实心圆，monochrome层不会自动解决。按用户统一资源的要求保留此系统行为差异，并在结果中说明。其他实体设备未实测，不能保证所有ROM都彩色或都能保留K·细节；有新ROM反馈时应先复测系统渲染，不重新加入未经要求的厂商名单。原有通知等正常更新或新通知后采用该资源，不批量重发。
