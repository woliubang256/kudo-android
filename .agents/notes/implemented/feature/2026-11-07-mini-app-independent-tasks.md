# Agent Note: 小程序使用账号隔离的独立任务

Status: implemented

## Problem

小程序仍是主 Activity 的网页路由，最近任务中与论坛混为一张卡片。用户确认每个小程序拥有独立任务，并从浮窗、桌面和深链恢复原实例。

## Decision

以账号与官方应用 ID 构成稳定文档身份，完整 query/hash 单独保留为启动地址。独立 MiniAppActivity 使用 documentLaunchMode=intoExisting 和 standard，重复打开同一应用恢复原任务，改变启动参数时才导航；不同应用及账号不共享运行实例。任务卡片使用应用名和图标。

小程序运行仍使用现有 WebScreen、官网 loader、Cookie 与受限兼容桥。关闭移除本任务；收起只将任务退到后台。进程回收后使用原入口重建，不承诺恢复第三方 JavaScript 的内存状态。账号变化关闭旧账号运行页，不从桌面入口自动切换账号。

稳定任务身份、独立 Activity 与清单、原生入口分流分别落地。浮窗、桌面和音乐控制在后续小功能中扩展此任务入口。

任务查询使用从 API 21 起可用的 RecentTaskInfo.id，避免 API 29 的 TaskInfo.taskId 在最低支持的 Android 26–28 上崩溃；Lint 的 NewApi 检查覆盖该兼容边界。

## Existing note audit

已检索活跃笔记中的任务、浮窗、桌面、音乐和 WebView 生命周期。现有[网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)与[沉浸运行页](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)部分重叠：只替代小程序在主路由内运行的承载方式，保留官网运行时、原生进度、普通网页连续导航和权限交接。[操作弹窗](../../implemented/feature/2026-11-07-mini-app-native-operation-dialogs.md)保持原网页业务回调。[账号会话](../../implemented/feature/2026-10-07-account-and-composer.md)继续管理实际登录。不归档仍有独立决定的笔记；无同主题 proposed/rejected。

## Alternatives considered

- 每次启动都创建新任务能隔离所有链接参数，但同一应用出现重复卡片并丢失恢复入口；任务身份只使用账号和应用 ID，参数交给原任务处理。
- 保留主路由并绘制假的任务预览改动较少，但不满足 Android 最近任务独立显示；使用真实文档任务。
- 重新实现本地小程序播放器能完全控制生命周期，但会复制官网鉴权、包校验与执行逻辑；继续使用官网宿主。

## Testing

MiniAppTaskRequestTest 的 3 项单元测试通过，覆盖同应用不同参数的稳定身份、不同账号/应用隔离、非法入口拒绝及参数保留（artifacts/mini-app-task-request-unit.txt）。Debug 与 AndroidTest 源码编译通过（artifacts/mini-app-independent-compile.txt），显式使用已安装的 0.3.5-dev / 3006。DeepLinkUiTest 增加任务交接后保留原生来源与完整参数的验证。

DeepLinkUiTest 的 10 项真机用例通过。浮窗集成的实际音乐应用与论坛分别使用任务 3898/3897，收起保留原运行上下文；用户确认从预览和已固定桌面图标恢复正常。两个小程序并行、切账号与强制进程回收未专项实测，不把身份单测记作这些设备行为已验收。

## Consequences

保留多个任务增加 WebView 内存占用；系统仍可能回收后台页面。账号未知或校验失败时阻止打开旧账号实例，保留重试。桌面入口的系统行为和启动器差异在对应功能单独验证。
