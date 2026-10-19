# Agent Note: 启动品牌连续变形与系统图标衔接

Status: implemented

## Problem

用户实机体验三色飞散原型后认为效果一般，改选 LinuxDO 圆形原地连续重组为酷 DO。旧原型还暴露系统图标转自绘图标时突然缩小的问题：平台会归一化启动矢量，不能把 iconView 的尺寸当作资源 viewport 的尺寸。

## Decision

沿用 core-splashscreen 1.2.0 的系统退出回调，不增加 Activity 或 Compose 启动页。750ms 包含 60ms 原标志、至 545ms 的连续重组、120ms 完成态定格和 85ms 淡出：黄色向上铺开与圆形变圆角方块、黑色上弧连续变成 K、白色中段收拢为右下角圆点。各色保持不透明的轮廓变化，不再拆片、旋转飞散或交叉淡变两张整图。首页和网络同时启动，动画不等待网络。

系统退出交接使用实际静态图标的可见像素范围，而不是猜测 OEM 归一化比例。仅交接时绘制一次小型透明位图，查找 alpha 边界并映射回启动层；后续逐帧只复用矢量 Path。不变更桌面图标。回到后台任务不重播，状态恢复或关闭系统动画时立即移除启动层；停止、销毁与结束清理幂等。

## Existing note audit

- [飞散提案](../../rejected/feature/2026-10-20-brand-launch-animation.md)为过时提案：其最强理由是三色独立轨迹直观对应品牌来源，但暗色下黑色碎片识别弱、600ms 内飞出再回收显得仓促，用户明确改选本方案。保留拒绝记录，避免以后重复实现同款飞散。
- [原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)部分重叠，保留 API 26 起兼容、初始化及 singleTop Intent 契约。
- [关于页](../../implemented/feature/2026-10-10-about-settings.md)部分重叠，仅复用品牌资产；其余 Logo 命中为通知、导航及主题，不取代这些决定。

## Alternatives considered

- 600ms 飞散回旋：来源与去向清晰、运动幅度显著，但真实用户已经否定观感，改为轮廓和色块连贯重组。
- AnimatedVectorDrawable：系统可在首帧前播放，但复杂轮廓在各版本的裁切和中间帧验证更难；沿用同一系统层的可控 Drawable，750ms 从退出回调可绘制时计时，不代表整个冷启动耗时。
- 固定放大 1.5 倍：当前 Android 13 可消除尺寸跳变，成本低；OEM 与旧版归一化不保证相同，改用实际绘制边界。
- 独立启动页或两图淡变：实现简单、预览方便，但会增加二次启动屏与图标回闪风险，且不符合连续变形要求。

## Testing

RMX2202 / Android 13 上，新增归一化图标交接测试先失败（56865 个差异像素，`artifacts/brand-launch-handoff-repro-device.txt`），修复后与首尾矢量、三色重组、不裁切、不整图淡变、主题图标、独立完成和幂等取消共 9 项通过，16.498 秒（`artifacts/brand-launch-morph-final-device.txt`）。首帧使用精确原始路径，避免额外 band 叠画和 clip 的抗锯齿边缘差异；中间帧白色区域按面积而非包围盒验证收拢，因为外圈尚有白边。

`brand-launch-morph-final.png` 的确定性真机绘制帧已目视检查；`brand-launch-morph-cold-resume.mp4` 及对应全屏、交接抽帧检查了真实冷启动到首页、后台返回：未见先出现酷 DO 再切回 LinuxDO，也没有原型的 2/3 缩小，恢复不重播。Debug 的 am start 报告 1628ms，只是本机一次启动计时，不代表 750ms 动画预算或帧率结论。

构建和手动 AndroidTest 显式使用 0.3.0 / 3001，核对开发证书后 adb install -r 保留数据，测试 APK 使用 -r -t 与 am instrument，不使用 connectedDebugAndroidTest 或模拟器。起始主题删除最低 API 26 不支持的直接 windowLightNavigationBar 属性，消除已复现的 NewApi Lint 错误。

最终 Debug Lint 与正式签名的 R8 Release 构建通过（`artifacts/brand-launch-morph-optimized-build.txt`）。为保持真机已有数据，正式包另用该机既有开发证书重新签名为 `brand-launch-morph-optimized-device.apk`，核对 0.3.0 / 3001、非 debuggable 及相同开发证书后覆盖安装；正式构建配置和原正式证书不变。安装后的 firstInstallTime 仍为 2026-10-17 01:33:30。优化包冷启动和后台恢复录屏 `brand-launch-morph-optimized-cold-resume.mp4` 及两张对应抽帧已目视复核：首个全屏品牌为 LinuxDO，交接没有缩小，原地重组成酷 DO 后淡出，后台恢复没有品牌层；此次 am start 报告冷启动 742ms、热恢复 81ms，仅作该次设备记录。

## Consequences

同一启动层承接系统图标，避免二次启动页、品牌回闪和固定归一化比例造成的尺寸跳变。750ms 不包含进程初始化时间，系统动画缩放会改变实播时长。实际图标边界读取增加一次最长边 512px 的位图绘制与 alpha 扫描，边界按像素取整，读不到时回退图标视图范围；逐帧仅复用轮廓。采样不是自动推导品牌的拓扑，修改资产后仍须检查首尾和中间形状。Android 26–30 和其他 OEM 需要后续实机验证，不能用当前真机或像素测试宣称所有设备稳定帧率；遇到原始图标无法软件绘制或非透明背景的设备时，须重新审视边界交接方案。
