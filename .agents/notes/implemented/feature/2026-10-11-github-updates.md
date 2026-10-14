# Agent Note: 关于页公开GitHub版本检查

Status: implemented

## Problem

关于页缺少版本检查和源码入口，用户要求未来公开仓库的Release成为版本及更新日志来源。

## Decision

通过独立无会话OkHttp客户端请求woliubang256/kudo-android的公开latest Release API，不带社区Cookie或GitHub凭据。只接受三段正式版本、比较数字分量，拒绝草稿与预发布。点击检查打开更新面板，展示版本、日期和Release正文；已有最新版本也可看日志。下载和安装由[应用内更新](2026-10-14-in-app-update-installation.md)接管，查看Release页面仍通过外部浏览器。检查期间去重，退出取消请求，错误可重试。404显示尚无公开版本而非已是最新；网络、限流及无效响应分别提示。源码入口始终外部打开仓库。

更新外壳原先沿用[共享面板](2026-10-10-coolapk-sheets.md)及80%可用高度上限；实色底部容器的选择由[玻璃更新Dialog](../bug-fix/2026-10-14-update-glass-dialog.md)部分取代，限高和固定区域契约保留。短日志按内容收缩，顶部版本信息紧凑排列并固定，中间日志独立滚动且可选中，下载或查看当前版本入口放在独立底部操作区，系统安全区避让计入限高。加载与错误分支保留可滚动内容和重试入口。

首页自动检查沿用公开正式 Release 接口和独立系统网络。每个 Activity 会话首次在前台显示首页时请求一次，状态由 Activity 级 ViewModel 持有，返回首页、切换标签、前后台切换及配置重建不重复请求。仅新版进入待展示状态；同版、旧版、404、限流、网络或解析失败均静默结束，手动检查仍可随时重试。离开首页时正在进行的检查继续完成，但结果只在首页恢复前台时展示，避免打断其他页面。关闭后本会话不再自动提醒，不跨进程保存忽略版本。

自动提醒复用更新面板的布局、日志和下载操作区，传入已经取得的 Release，不为显示面板再次请求网络。自动检查没有加载或错误面板。

## Existing note audit

[关于页](../../implemented/feature/2026-10-10-about-settings.md)部分重叠，保留品牌与已有三项入口；[签名与发布](../../implemented/process/2026-10-08-signing-and-tag-releases.md)继续约束三段版本和APK签名。没有现有更新协议、完全吸收或过时提案。

## Alternatives considered

- 直接打开Release网页最简单，但不能在App显示新版本与日志；原生读取API并提供外部下载入口。
- 内置APK下载及安装能减少跳转，但最初缺少下载生命周期和安装权限能力，因此先使用Release页提供附件下载；用户后续明确要求内置更新后，由[应用内更新](2026-10-14-in-app-update-installation.md)接管此取舍，版本检查与日志读取保留。
- 整个面板一起滚动实现简单，也能容纳任意长度的日志，但长日志将下载按钮推到屏幕外，滚动时也会移走版本信息；改为仅日志滚动并复用固定底部操作区。
- 每次返回首页都检查能更快发现启动后发布的新版本，但会增加请求并重复打断阅读；自动检查限制为会话一次，用户需要时仍可手动检查。
- 自动检查发现新版后让手动面板重新检查可以完全复用原调用方式，但增加网络请求，且第二次失败会让已知的新版变成错误提示；面板支持直接显示已有结果。

## Verification

首页自动检查新增 HomeUpdateViewModelTest 三项与 HomeUpdateUiTest 五项：请求进行中去重、关闭后不重查、新会话重查、同版/旧版/无效版本/网络失败静默、会话结束取消请求、仅前台首页触发、离开首页后迟到结果延后展示、前后台及状态恢复不重复。与 GitHubUpdatesTest 原五项 JVM、UpdateUiTest 七项真机回归全部通过；构建日志 artifacts/home-update-build.txt、真机日志 artifacts/home-update-device.txt。真机仍为 RMX2202 / Android13，显式版本 2005 / 0.2.3，同签名覆盖安装保留数据。新版结果使用离线夹具，没有实际安装 Release。

限高修复前，真机新增3项用例确认两个失败：640dp约束下长日志面板高度1884px，超过80%上限1507.2px；320×360dp、深色130%字号下下载入口初始不可见，短日志自然收缩用例通过。日志 artifacts/update-height-repro-device-v4.txt。夹具用外层全尺寸Box释放根节点最小约束，确保内层短窗口真实生效；显式使用合法三段Debug版本0.2.3 / 2005，避免0.2.2-dev被现有正式版本解析规则拒绝而误测错误分支。

离线夹具验证数字排序、同版旧版、草稿预发布、404、限流、网络失败、空日志、重试以及源码外部回调。

GitHubUpdatesTest的5项JVM测试通过，末项断网用例记录于artifacts/github-network-test.txt。RMX2202 / Android13上UpdateUiTest的4项通过，验证外部源码回调、新版日志和下载回调、错误重试与同版空日志、退出取消请求以及360dp窗口130%字号长日志可滚动。AboutPageUiTest的4项同时通过，日志artifacts/update-device-tests.txt；同批独立开关像素测试失败另行修正，不归为更新功能失败。没有向私有仓库注入凭据或创建真实Release。

实际账号About页截图artifacts/interface-about-live.png已查看。真机从“检查更新”匿名请求当前仓库，展示“暂无公开版本，仓库可能尚未公开或尚未发布正式版本”、重试和仓库入口，记录artifacts/interface-update-live.json；未误报已是最新。

限高修复后，RMX2202 / Android13上UpdateUiTest的7项全部通过：完整窗口60条日志、80%高度边界、320×360dp深色130%字号下触摸滚动及标题/下载位置不变、短日志收缩、新版外部下载回调、当前版本查看入口、错误重试和关闭取消请求。日志 artifacts/update-height-final-device.txt；共享面板另有10项相关回归通过，见 artifacts/update-panel-cap-device.txt。扩展运行的AboutPageUiTest有5项通过、1项既有失败：settingsKeepsShortSiteLinkTitleAndMovesAllAboutEntriesOut使用performScrollTo直接查找LazyColumn尚未组合的屏幕外“站内链接”，SettingsScreen与该测试本次均未修改，不计入本次修复通过数。

已查看真机生成的截图 artifacts/update-long-full-window.png、artifacts/update-height-long-light.png、artifacts/update-short-dark-large-scrolled.png、artifacts/update-short-notes.png，确认日志区域裁切及底部操作可见；短窗口截图采用真实布局约束。显式版本0.2.3 / 2005构建Debug与测试APK通过，GitHubUpdatesTest的5项单测通过，Lint为0错误、45条警告和1条提示，变更文件无Lint问题；日志 artifacts/update-height-final-build.txt。同签名覆盖安装，保留现有数据和应用，测试结束已回到MainActivity。

## Consequences

App与GitHub Release共用一份日志，避免维护两套版本说明；代价是检查依赖GitHub网络和公开正式版本。私有仓库公开前匿名API为404；GitHub网络不可达时检查失败。当前本地Debug签名不能直接被正式签名APK覆盖。

固定版本信息和底部操作会减少矮屏的日志可视行数，日志本身仍可滚动。当前实际设备覆盖Android13，其他系统与设备的系统栏、极端窗口尺寸尚未真机验证。
