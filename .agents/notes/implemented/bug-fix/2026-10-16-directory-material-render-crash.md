# Agent Note: 修复分类与消息工具栏材质渲染崩溃

Status: implemented

## Problem

Android 13 的 RMX2202 真机点击底部“分类”后，应用进程在 RenderThread 的 `libhwui.so` 中收到 `SIGSEGV`。堆栈重复 RenderNode::prepareTreeImpl 和 SkiaDisplayList::prepareListAndChildren：分类工具栏引用包含自身的祖先 LayerBackdrop，形成循环渲染依赖。移除 lens 仍崩溃，移除整个 drawPlainBackdrop 才通过，因此不是 lens 参数或 Android 13 特有的兼容性问题。

消息操作栏具有相同的祖先采样结构，独立离线复现测试也触发 RenderThread SIGSEGV 与 stack overflow。用户确认一起修复，不改变现有分组、按钮布局或材质选项。

## Decision

分类工具栏与内容采样层为兄弟节点，采样源只包含页面背景、布局占位和目录内容，不包含调用 drawPlainBackdrop 的工具栏。工具栏高度实时测量并留出等高位置，保留安全区、字号变化和目录原有布局。按 GlassMode 选择的 blur/vibrancy/lens 保持不变，不禁用硬件加速、不添加设备黑名单、不降级用户材质。

消息采用同一采样隔离约束：HorizontalPager 是内容采样层，顶部 padding 按实测工具栏高度预留；材质工具栏后绘制为兄弟节点，安全区包含在材质表面内。Tab、刷新和全部已读继续同行，通知读取、分页、标读与账号切换逻辑不变。

## Existing note audit

已检索分类目录、首页材质、Backdrop、循环采样和 RenderThread 记录。既有[类别与标签目录](../../implemented/feature/2026-10-15-community-directory.md)保留材质选择和目录行为；[液态玻璃 UI](../../implemented/feature/2026-10-07-liquid-glass-ui.md)与[网络管理](../../implemented/feature/2026-10-15-network-request-scheduling.md)已明确镜片不能包含在自身采样源，本次将同一约束应用到目录工具栏。这些是部分重叠，不取代旧决策；没有同主题 rejected 提案。

消息检索命中[个人中心与消息分组](../feature/2026-10-10-member-account-dashboard.md)和[通知传输](../architecture/2026-10-10-notification-stream.md)，前者布局与后者会话、分页、只读边界继续保留，本篇只承担采样环修复；与通知颜色图标 rejected 提案无关，不存在需要替代或归档的消息决策。

## Alternatives considered

- 只移除 lens 改动最小且可能避开 shader 兼容问题，但实际真机复现仍崩溃，不能消除采样环。
- 移除整个分类页材质已在真机证明能避开崩溃，但会丢失用户选择的液态/模糊效果，只作为诊断步骤，不是最终修复。
- 调整采样源为工具栏的兄弟节点稍增布局结构，但直接消除渲染环并保留既有材质和硬件加速，采用此方案。

## Testing

- 原实现运行新增复现用例导致 native crash；只去掉 lens 仍失败。
- 显式以 versionCode=2011、versionName=0.2.6-dev 构建开发包与测试包，通过签名核对后覆盖安装，保留真机数据。
- RMX2202 / Android 13 上 CommunityDirectoryUiTest 7/7 通过；新增用例覆盖浅深色、三种材质、首页内嵌与独立入口共12组合，检查反复进入、类别/标签切换、工具栏不在采样源后代、内容不与工具栏重叠，并强制截图渲染。
- 原有筛选、网格、分页、错误与返回滚动恢复用例通过。实际启动 MainActivity 点击底部分类后，真实目录与顶部类别/标签正常显示，进程存活且 crash buffer 为空。
- 构建、测试和实际入口证据分别保存在 artifacts/directory-render-isolated-build.txt、artifacts/directory-render-isolated-device.txt、artifacts/directory-render-live.png 与相应 crash 日志，不纳入源码提交。
- 消息未修复版本运行新增 notificationsMaterialSurvivesSwitchingIntoThePageWithoutSamplingItsToolbar 用例，得到 Process crashed、RenderThread SIGSEGV 与原生栈溢出，证据为 artifacts/notifications-material-repro-device.txt 和 notifications-material-repro-crash.txt。
- 修复后 MemberAccountUiTest 与 GuestNotificationsUiTest 共7/7通过（artifacts/notifications-material-fixed-device.txt），crash buffer为空。新增用例在浅深色与三种材质共6组合反复进入消息页、切换回复分组并截图，检查工具栏不在采样源后代、通知不被遮挡、Tab与两个图标同行、无独立消息标题、没有任何夹具写请求。既有跨页筛选、解决通知导航与夹具标读、会话过期清缓存、窄屏大字号与个人入口用例保持通过。
- 实际账号从MainActivity进入消息可读取真实通知；分类与消息往返3轮，PID保持31365且crash buffer为空。最终布局中四个Tab和刷新、全部已读图标中心y均为179px，截图 artifacts/notifications-material-live-final.png。未点击真实通知或全部已读；签名一致的开发版2011 / 0.2.6-dev覆盖后，登录仍在，firstInstallTime仍为2026-10-13 04:31:25。

## Consequences

消除渲染环且保留用户材质，代价是增加兄弟层和高度占位。未来把工具栏重新包进采样源会再次形成渲染环，需保留真机回归；模拟截图通过不等于所有 GPU 和系统版本均已验收。真实目录接口可能受 Cloudflare 验证限制，与本地渲染稳定性分开记录。
