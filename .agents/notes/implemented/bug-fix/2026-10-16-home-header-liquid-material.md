# Agent Note: 首页吸顶延续现有液态玻璃

Status: implemented

## Problem

首页吸顶已经采样真实列表，但较浓的主题底色与 14dp 模糊把图片细节糊成磨砂。用户明确参考只用于吸顶位置，不应改变应用已有玻璃材质。原来的红蓝封面测试能证明实时采样，不能区分磨砂与液态玻璃。

## Decision

保留全屏 Pager 采样、搜索行折叠、顶部安全区、标签形状与所有交互，首页顶部对齐现有底栏的光学参数：20% 主题表面色、4dp 模糊、液态模式 24 / 24dp 边缘折射。模糊与折射随折叠比例进入，展开时仍为实色；API 31 支持模糊，API 33 的液态模式支持折射，半透明和旧系统采用 94% 表面色。

矩形顶部继续使用 drawPlainBackdrop，不引入悬浮胶囊外形、按压放大或默认白色高光边框。lens 只接受 CornerBasedShape / RoundedRectangularShape，因此用 RoundedCornerShape(0.dp) 保持平直矩形，而不能传 RectangleShape。回归增加明暗条纹，要求吸顶后保留至少一半原始对比度，同时继续检查浅深色实时透色、反向展开恢复实色。

## Existing note audit

- [首页标签与折叠](../feature/2026-10-08-home-tabs-and-collapse.md)：部分取代，仅替换浓模糊、无折射的材质决策；布局、手势和历史验证继续有效，双方互链。
- [官方玻璃导航](../architecture/2026-10-07-backdrop-demo-navigation.md)：部分重叠，复用底栏既有光学参数和系统降级边界，不改变其导航契约。
- [玻璃界面](../feature/2026-10-07-liquid-glass-ui.md)、密度、刷新、会话筛选及其余首页关键词命中：本次不改变它们的业务和布局决定；拟议及否决目录无同主题替代方案。

## Alternatives considered

- 保留 14dp 模糊和 54% 主题底色：最能压住滚动图片的干扰并保证文字对比，但用户已明确要求现有透明玻璃；不能把可读性优化变成未经确认的磨砂替换。
- 仅降低模糊和底色，不加折射：改动最少且可以保留图片细节，但仍缺少当前液态模式的边缘光学效果；沿用底栏相同的折射能力分级。

## Testing

旧材质的条纹对比度为 0.1804，低于原始明暗对比度一半（0.3431），在修改运行时代码前失败，记录 artifacts/home-header-liquid-repro.txt。首次试用 0.16 的固定门槛误过，因此改用保留原始对比度比例的要求，不把“能透出颜色”当成足够细节。回归继续覆盖浅深色实时透色和反向展开恢复实色，截图单独检查条纹细节和真实首页吸顶，不以测试通过代替材质验收。

显式使用 versionCode=2011、versionName=0.2.6-dev 构建，核对与已安装 APK 的 SHA-256 证书一致后 adb install -r 覆盖，测试包单独 install -r -t 并手动 am instrument，保留真机账号数据。

首次接入折射的 5 项回归中 3 项滚动场景因 RectangleShape 不受 lens 支持而失败，记录 artifacts/home-header-liquid-tests.txt；这不是协程清理问题，根因是折射形状契约。改用零圆角 CornerBasedShape，不移除折射来绕开异常。

最终 Debug / AndroidTest 构建成功，记录 artifacts/home-header-liquid-fixed-build.txt。RMX2202 / Android 13 上 FeedHeaderUiTest 5 项全部通过（15.773 秒，artifacts/home-header-liquid-fixed-tests.txt），覆盖吸顶稳定、反向展开、首屏避让、账号标签、窄屏大字号与刷新，以及浅深色真实采样和条纹细节。两种主题的条纹对比度均为 0.4510，超过 0.3431 门槛。此前已通过的分页和阅读位置测试未因本次仅改材质而重复全量执行。

材质人工验收独立记录：已查看 artifacts/home-header-liquid-review.png 中旧版与浅深色红蓝条纹的对照，底层明暗不再被浓底色压平，边缘存在折射。真实首页的展开、吸顶正文及图片经过顶部截图分别为 home-header-liquid-real-expanded.png、home-header-liquid-real-pinned.png、home-header-liquid-real-image.png，状态栏和标签背后的颜色随列表变化，展开保持实色。账号仍为原登录态，版本仍为 2011 / 0.2.6-dev；没有卸载或清数据。仅验证此机，不外推旧系统、其他设备或帧率。

## Consequences

顶部延续现有玻璃的透光、轻模糊和折射，而不是为参考图另造一套磨砂。较透明的顶部会让高对比图片更明显；极端背景和旧系统仍需人工复核。矩形顶栏与胶囊底栏的外形不同，相同参数不代表两者逐像素相同。条纹只守住细节对比，不单独证明折射正确或帧率达标。
