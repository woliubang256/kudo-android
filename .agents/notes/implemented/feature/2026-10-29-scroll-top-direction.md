# Agent Note: 回顶按钮只在向上浏览时显示

Status: implemented

## Problem

回顶按钮当前只判断距离，持续向下阅读时也会显示。用户要求类似 Telegram：向下隐藏，向上且超过原距离门槛才显示，停止后维持最后方向。

## Decision

保留现有视口距离算法、样式、位置、遮挡和生命周期规则，在公共目标注册中增加明确的向上方向条件。Compose 列表、网格和普通滚动使用各自滚动状态记录的最后方向；反向聊天按视觉顶部换算。WebView 使用真实 scrollY 的前后变化，开始导航时重置方向。初次恢复位置不代表用户向上滚动，点击回顶期间隐藏入口，失败后仍允许重试。

## Existing note audit

检索 quick-scroll、回顶、滚动方向及相关网页笔记。[快速回顶](../../implemented/feature/2026-10-11-quick-scroll-to-top.md)部分取代：只替换距离足够即显示的规则，保留距离、分页、回顶动作和材质决定。[网页浏览](../../implemented/feature/2026-10-10-in-app-web-links.md)只复用真实滚动监听，不改变网页路由。其余命中为页面布局和既往测试记录，无同主题 proposed/rejected，也无完全取代项。

## Alternatives considered

- 公共 Modifier 监听嵌套滚动能区分手势与程序跳转，但依赖 Modifier 顺序，普通 ScrollState 已有不同接入顺序，WebView 又不共享同一分发链；使用滚动状态最后方向和网页原生回调，不更换原有嵌套滚动结构。
- 保持仅距离触发无需改动接入，但向下阅读会持续遮挡内容，不满足新的交互要求。

## Consequences

- 向下经过原门槛不显示，向上仍在门槛外显示，再向下隐藏，停止不自行超时。
- 列表长首项、网格、普通滚动、网页保留原距离门槛；恢复位置默认隐藏。
- 反向聊天保持回到最早已加载消息，远楼层窗口保留失败重试；帖子目录按钮不受方向控制。
- 点击回顶、实时设置、分页切换、面板遮挡及材质避让通过相关回归。

最后滚动方向也包含实际发生的程序滚动；不为了区分每一种程序定位改写所有页面滚动。网页脚本滚动同样能更新方向。真实材质观感与设备兼容性不由方向语义测试证明。

## Testing

先更新列表和网页方向用例，在旧实现上捕捉到两项失败：向下越过门槛仍显示按钮，日志 artifacts/scroll-direction-repro-device.txt。修复没有改变 ScrollTopDistance 或视口计算，只给原有门槛叠加方向和回顶执行状态。

最终 14 项 QuickScrollToTopUiTest 与 14 项 WebScreenUiTest 通过。联合运行日志 artifacts/scroll-connect-final-device.txt 中 43/44 项先通过；个人主页一项在明确滚动距离后单独通过，日志 artifacts/scroll-profile-final-device.txt，合计 44 个不同关联 UI 用例通过。覆盖方向反复切换、停留不超时、列表和长首项、网格、普通滚动、网页、恢复位置、反向聊天、分页保留、远楼层失败重试、遮挡、目录附件、设置与三种材质避让。

手势夹具使用慢速短距离防止快速惯性直接越过门槛；主页列表语义覆盖整页而非仅可见内容区，触摸改在下半部避开叠加页签，显式定位第 25 项确保超一屏，回顶后检查实际页头高度而非整页列表上边界。没有为测试调整运行时距离或页头逻辑。

已查看 artifacts/scroll-direction-threshold-short.png、scroll-direction-threshold-distant.png 与 scroll-direction-material-liquid.png：短距离无按钮，远距离向上后显示，32dp 圆面和底栏避让保持。截图为离线夹具，不代表所有设备的材质观感。Debug、AndroidTest 和 lintDebug 构建通过，Lint 为 0 错误、58 条既有警告、1 条提示；联合构建记录 artifacts/connect-native-fixed-build.txt，最终测试夹具构建记录 artifacts/scroll-profile-final-build.txt。RMX2202 / Android 13 上保持 3002 / 0.3.1-dev，核对签名后仅覆盖安装，未卸载、清数据或更改真实账号。
