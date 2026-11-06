# Agent Note: 小程序评分、积分打赏与举报

Status: implemented

## Problem

原生小程序隐藏官网工具栏后，更多宫格缺少评分、积分打赏和举报。公开关于接口不包含当前账号权限、个人评分和运行版本，不能直接用于写操作。

## Decision

在既有官网来源限定的 WebView 桥上增加原生发起、请求编号匹配的固定操作集。通过当前页面会话访问 production info、rating、tip、report 与站点举报配置；CSRF 只保留在官网脚本内，不传给原生。游戏 iframe 不能选择接口或触发原生写操作。

原生宫格补齐三项，弹窗沿用小程序普通材质，不采样 WebView。五星点选可修改，成功后同步原生关于统计；打赏读取服务端金额、余额及 next_tip_at，选定后显式确认，同一笔失败重试沿用 request_id；举报只使用 enabled 且 applies_to 包含 DiscourseMiniApp::App 的原因，排除 notify_user，必要说明遵循站点字数配置。举报的 release_id 来自当前宿主实际 launch 响应，缺失时拒绝提交，不能拿最新 info 的版本代替已运行版本。

请求绑定完整页面地址、应用 ID 和当前账号上下文。切页、退出、账号变化使旧读取与回调失效；提交中阻止重复点击，网络结果未确认时保留打赏幂等编号，举报则阻止重复提交并提示网站核对。已经扣款导致的余额下降或冷却不阻止用原编号核对；结果未确认前不能改金额发起另一笔。成功及错误消息保留，其他内容不加操作说明小字。

新操作继续要求前台；同账号、同页面已发出的操作结果允许在后台正常结算，避免暂停时丢弃结果后菜单一直忙碌。控制器取消与代际检查同时阻止不响应取消的迟到回调。打赏编号和举报锁只在当前账号、当前运行页会话内保留，退出或重新进入后以服务端余额、冷却和权限为准，不自动重放写操作。

## Website contract

官网页面与插件源码已核对：`GET /mini-app-api/production/<id>/info` 返回权限和个人评分，`PUT .../rating` 提交 score；`GET .../tip` 返回 amounts、balance、next_tip_at，`POST .../tip` 提交 amount 与 request_id；`POST .../report` 使用 flag_type_id、release_id 和必要的 message。举报配置从 `/site.json` 与预加载 siteSettings 读取，不复用只适用于帖子的原因过滤器。

DocumentStart 脚本只观察顶层官网 production launch 的首个匹配 release 身份；后续令牌续期不能更改已运行版本。只提取 release.id/app_id/channel，不传输或保存 runtime_token、runtime_context、package_password；无可观测运行版本时明确禁用举报。原生只接受匹配自己请求编号的固定操作结果，不提供任意地址或方法的网络桥。官网核对及自动化测试均未提交真实评分、积分打赏或举报。

## Existing note audit

[小程序网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)部分重叠：继续保留受限来源、关于展示及网页普通材质，本篇新增独立写入契约；[沉浸运行视图](../../implemented/feature/2026-11-04-mini-app-immersive-runtime.md)约束运行页不重建，本篇不改变沙箱或加载过程；[剪贴板记忆](../../implemented/feature/2026-11-05-mini-app-remembered-clipboard-consent.md)只共用账号及生命周期隔离，授权范围保持。未发现同主题 proposed/rejected 笔记。

## Alternatives considered

- 直接点官网隐藏按钮可完全沿用网页交互，但隐藏布局下网页菜单位置、输入和遮挡难与原生更多面板协调，也不能满足已确认的原生弹窗方案。
- 通过 CommunityRepository 请求接口能共用现成 CSRF 与网络错误处理，但运行版本仍属于网页宿主，额外维护两套会话与状态同步会增加契约；本次沿用既有官网 WebView 会话及精确来源消息桥，固定操作而非通用 HTTP 接口。
- 使用 production info 中的 release_id 最容易实现，却可能举报成运行后刚更新的新版本；只使用实际 launch 元数据。

## Testing

- MiniAppActionsTest 的 10 项与 MiniAppInfoTest 的 7 项定向 JVM 测试通过，覆盖数据校验、重复提交、余额和冷却边界、同编号打赏核对、当前运行版本举报、字数与账号隔离。已扣款后无法核对先由 `artifacts/mini-app-tip-reconcile-repro.txt` 复现，再修复至通过。
- 新增 MiniAppActionsUiTest 使用真实 WebView 和离线官网响应，覆盖评分修改及关于统计、显式打赏确认、运行版本与原因过滤、访客登录、服务端禁止与举报结果未确认、六种主题材质和 130% 字体，以及后台结果结算。后台丢弃在途结果先在 `artifacts/mini-app-actions-background-repro-device.txt` 失败，分离会话有效与前台限制后通过；后台完成后再检查关于评分也通过。
- 夹具初始化曾因内部 WebView.id 遮蔽应用 UUID、DocumentStart 脚本顺序和小数评分无障碍文案断言失败；修正夹具并把 mock fetch 与动作脚本在同一 DocumentStart 中安装，未修改生产协议以适配夹具。
- 真机浅深评分、打赏、举报和更多宫格截图已目视检查，文件为 `artifacts/phone-mini-app-rating-light.png`、`phone-mini-app-rating-dark.png`、`phone-mini-app-tip-dark.png`、`phone-mini-app-report-dark.png` 和 `phone-mini-app-community-menu.png`。中文可读，普通材质不采样 WebView；截图是离线数据，不代替真实交易验收。
- Debug、AndroidTest 构建与 Lint 通过，日志为 `artifacts/mini-app-actions-final-build.txt` 与 `artifacts/mini-app-actions-verified-build.txt`；Lint 为 0 errors、71 warnings，新增动作文件无警告。
- 首轮模拟器 48 项相关界面回归中 46 项通过，两项旧用例在连续全屏点击与外部导航计数上失败。全屏诊断确认 pointerdown 到达按钮而 click 被浏览器抑制，等待实际退出与 500ms 手势间隔后单项通过；外部导航改为等待目标页面再检查计数，保留原有请求数和地址断言。用户接入真机后关闭模拟器，最终验收以真机结果为准。
- RMX2202 真机运行新增动作与既有网页测试共 48 项（92.744 秒），47 项通过，旧剪贴板用例 siteClipboardReadWriteRequireConsentEvenWhenBrowserApisSucceed 在首次等待授权提示时超时；该用例同机单独复测通过（2.335 秒），没有修改剪贴板业务来规避失败。新增 7 项、之前连续全屏与外部导航断言全部在真机通过，日志为 `artifacts/mini-app-actions-phone-device.txt` 与 `artifacts/mini-app-actions-phone-clipboard-recheck.txt`。

## Consequences

原生交互复用官网当前会话和鉴权，且保持游戏 WebView 不重建；代价是维护固定接口、launch 响应及举报模型的适配。官网协议变化时显示明确错误，需要重访相应契约测试；仅在实际观察到当前版本后允许举报，无 DocumentStart 支持或宿主加载已结束时不能猜测版本。

最终设备验证使用 RMX2202 / Android 13。覆盖前核对开发版 0.3.1-dev / 3002 与 APK 的签名一致（SHA-256 为 95f95aff…6623e4f），使用显式构建的 0.3.5-dev / 3006，通过 adb install -r、adb install -r -t 和 am instrument 运行，不卸载、不清理数据。真实登录后的评分写入、扣积分与举报不自动提交，离线自动化不冒充线上交易成功。

真机自动回归之后，直接以小程序链接启动开发版进行线上只读检查被自动审批拒绝，返回仅为 blocked by policy，未改用浏览器或其他入口重试；本次不宣称完成真机线上接口验收。
