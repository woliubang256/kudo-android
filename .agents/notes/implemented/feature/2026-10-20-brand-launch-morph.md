# Agent Note: 启动品牌连续变形与系统图标衔接

Status: implemented

## Problem

用户实机体验三色飞散原型后认为效果一般，改选 LinuxDO 圆形原地连续重组为酷 DO。旧原型还暴露系统图标转自绘图标时突然缩小的问题：平台会归一化启动矢量，不能把 iconView 的尺寸当作资源 viewport 的尺寸。

## Decision

沿用 core-splashscreen 1.2.0 的系统退出回调，不增加 Activity 或 Compose 启动页。900ms 包含 60ms 原标志、至 545ms 的连续重组、205ms 完成态定格和 150ms 整层淡出：黄色向上铺开与圆形变圆角方块、黑色上弧连续变成 K、白色中段收拢为右下角圆点。保留原 750ms 版本的原标志与变形节奏，仅延长酷 DO 显示；750–900ms 整个启动层（图标和背景）从不透明线性降至透明，让下方首页逐渐显现，而非只淡出图标。各色保持不透明的轮廓变化，不再拆片、旋转飞散或交叉淡变两张整图。首页和网络同时启动，动画不等待网络。

系统退出交接使用实际静态图标的可见像素范围，而不是猜测 OEM 归一化比例。仅交接时绘制一次小型透明位图，查找 alpha 边界并映射回启动层；后续逐帧只复用矢量 Path。不变更桌面图标。回到后台任务不重播，状态恢复或关闭系统动画时立即移除启动层；停止、销毁与结束清理幂等。

## Existing note audit

- [飞散提案](../../rejected/feature/2026-10-20-brand-launch-animation.md)为过时提案：其最强理由是三色独立轨迹直观对应品牌来源，但暗色下黑色碎片识别弱、600ms 内飞出再回收显得仓促，用户明确改选本方案。保留拒绝记录，避免以后重复实现同款飞散。
- [系统提前播放提案](../../rejected/bug-fix/2026-10-20-native-launch-timeline.md)被拒绝：它能在首页初始化时先播放，减少静态 LinuxDO 等待，但实际曝光仍受启动就绪影响；用户真机否定后明确恢复 Canvas 原节奏，只增加末尾整层渐隐，不再保留 AVD 资源或原生剩余时长分支。
- [原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)部分重叠，保留 API 26 起兼容、初始化及 singleTop Intent 契约。
- [关于页](../../implemented/feature/2026-10-10-about-settings.md)部分重叠，仅复用品牌资产；其余 Logo 命中为通知、导航及主题，不取代这些决定。

## Alternatives considered

- 600ms 飞散回旋：来源与去向清晰、运动幅度显著，但真实用户已经否定观感，改为轮廓和色块连贯重组。
- AnimatedVectorDrawable：系统可在首帧前播放，但复杂轮廓在各版本的裁切和中间帧验证更难，且试装后用户否定实际效果；沿用同一系统层的可控 Drawable，900ms 从退出回调可绘制时计时，不代表整个冷启动耗时。
- 固定放大 1.5 倍：当前 Android 13 可消除尺寸跳变，成本低；OEM 与旧版归一化不保证相同，改用实际绘制边界。
- 独立启动页或两图淡变：实现简单、预览方便，但会增加二次启动屏与图标回闪风险，且不符合连续变形要求。

## Testing

RMX2202 / Android 13 上，900ms 时序回归先在原 750ms 实现失败（expected 900 but was 750，`artifacts/brand-launch-900-repro-device.txt`）。调整后，900ms 总长、750ms 前整层不透明、最后 150ms 线性淡出（825ms 为半透明）、原始首尾矢量、归一化交接、三色重组、不裁切、不整图淡变、主题图标、独立完成和幂等取消共 11 项通过，19.345 秒（`artifacts/brand-launch-900-final-device.txt`）。

归一化图标交接原始回归曾有 56865 个差异像素（`artifacts/brand-launch-handoff-repro-device.txt`），当前继续保留该像素测试。首帧使用精确原始路径，避免额外 band 叠画和 clip 的抗锯齿边缘差异；中间帧白色区域按面积而非包围盒验证收拢，因为外圈尚有白边。`artifacts/brand-launch-900-morph.png` 的确定性真机绘制帧已目视检查，原 60–545ms 变形阈值不变。

构建和手动 AndroidTest 显式使用 0.3.0 / 3001，核对开发证书后 adb install -r 保留数据，测试 APK 使用 -r -t 与 am instrument，不使用 connectedDebugAndroidTest 或模拟器。Debug 与测试 APK 构建通过；Debug Lint 与正式签名的 R8 Release 构建通过，2 分 43 秒（`artifacts/brand-launch-900-optimized-build.txt`）。正式包另用该机既有开发证书重新签名为 `artifacts/brand-launch-900-optimized-device.apk`，核对版本、非 debuggable 及相同开发证书后覆盖安装；原正式签名包及签名配置不变。安装后的 firstInstallTime 仍为 2026-10-17 01:33:30。

优化包录屏 `artifacts/brand-launch-900-optimized-cold-resume.mp4` 及对应全屏、交接、末尾渐隐抽帧已目视复核：首个全屏品牌为 LinuxDO，交接没有缩小，酷 DO 完成后定格，最后图标与背景一起渐隐、首页全屏逐渐显现，后台恢复没有品牌层。首页截图 `artifacts/brand-launch-900-home.png` 中文可读。此次 am start 报告冷启动 746ms、热恢复 77ms，只作本机一次启动记录，不代表 900ms 动画预算或帧率结论；本次未改动系统动画缩放（1）。

## Consequences

同一启动层承接系统图标，避免二次启动页、品牌回闪和固定归一化比例造成的尺寸跳变。900ms 不包含进程初始化时间，系统动画缩放会改变实播时长；相较原版增加 150ms，延长的是酷 DO 完成态及其与首页的整层衔接，不缩短或重排变形。实际图标边界读取增加一次最长边 512px 的位图绘制与 alpha 扫描，边界按像素取整，读不到时回退图标视图范围；逐帧仅复用轮廓。采样不是自动推导品牌的拓扑，修改资产后仍须检查首尾和中间形状。Android 26–30 和其他 OEM 需要后续实机验证，不能用当前真机或像素测试宣称所有设备稳定帧率；遇到原始图标无法软件绘制或非透明背景的设备时，须重新审视边界交接方案。
