# Agent Note: WebView 页面统一使用普通材质

Status: implemented

## Problem

用户反馈从帖子打开 CDK、信任等级等 Linux.do 子站时闪退，关闭“网页高级材质”后恢复；另有反馈称卸载系统 WebView 更新后恢复。两条线索指向 WebView 版本与高级材质路径的兼容风险，但没有受影响设备的崩溃栈，不能认定具体内核或 GPU 缺陷。用户决定 WebView 页面统一不再接入高级材质。

## Decision

登录、网页验证和应用内网页阅读统一使用固定普通的 WebPageScaffold：顶部与有界 WebView 视口顺序排列，清空继承的顶部采样源，不提供高级模式参数。移除“网页高级材质”设置和 DisplaySettings 字段，停止读取、写入旧键 `advanced_webview_material`；旧版本曾保存 true 的用户升级后同样使用普通布局，不清空其他偏好或账号数据。

普通布局复用目前关闭开关时的行为。原生页面继续使用 MaterialPageScaffold 和原有全局材质选择；不改网页链接分流、Cookie、验证码视口、历史恢复或渲染进程退出逻辑。全局回顶及脚注宿主既有的采样行为不属于旧开关控制范围，本次不把局部顶部采样退出描述成整个应用不存在 WebView 祖先采样层。

## Existing note audit

已检索 proposed、implemented、rejected 中的 WebView、网页高级材质、普通网页与顶部采样记录。[登录按钮与网页材质偏好](../../implemented/bug-fix/2026-10-16-login-button-double-click.md)部分重叠：本篇取代可选网页高级模式及其偏好契约，保留单次完成点击、验证码视口和历史验收事实。[顶部材质](../../implemented/feature/2026-10-16-page-toolbar-material.md)部分重叠：保留原生页面材质与采样隔离约束，网页改为固定普通布局。两篇均保留独立决定，不归档。网页链接、外部应用打开、SVG 签名和编辑面板记录不定义本次移除的网页顶部开关，保持原决策；未发现同主题待实施或被否提案。

## Alternatives considered

- 保留开关并默认关闭能让兼容设备继续使用效果，且改动最少；已有用户开启后遇到闪退，升级也会保留旧的 true，不能满足统一取消高级材质的决定。
- 按 WebView 版本或机型降级能保留更多设备上的效果，但缺少可靠的受影响版本范围与崩溃栈，维护黑名单也无法覆盖内核更新组合，因此不采用。
- 保留原布局但隔离 WebView 与玻璃采样可以同时争取效果和兼容性，不过仍需受影响设备验证，而且用户已选择统一普通材质，本次不继续投入这条路径。

## Testing

- 运行时代码改动前，新增的旧偏好失效、设置项移除和登录无采样三项用例在原实现上全部失败，分别得到旧字段仍为 true、旧设置节点仍存在、material-page-content 仍存在的断言。证据为 artifacts/webview-ordinary-repro-device.txt；这证明缺少统一普通布局约束，不代表已复现受影响用户的原生闪退。
- WebViewMaterialLayoutUiTest 覆盖浅深色与三种全局材质，检查继承的工具栏采样和操作作用域被清空、无网页顶部采样层、内容位于顶部下方且没有重复顶部留白。WebScreenUiTest 覆盖相同六种组合中切换原生材质不重建或重载网页。
- WebViewMaterialSettingsUiTest 使用隔离偏好文件验证旧 true 不再生效、设置项不再出现，并检查更新和重新读取后主题、全局材质、NSFW 与回顶等其他偏好。LoginWebViewUiTest 用隔离旧偏好验证普通布局、验证码视口随空间缩放且不重载、单次完成点击；VerificationUiTest 覆盖六种组合反复进入验证页。
- RMX2202 / Android 13 测试机已安装开发版为 0.3.1-dev / 3002，WebView 为 com.google.android.webview 155.0.8059.43。构建显式使用相同版本，APK SHA-256 签名指纹核对一致后使用 adb install -r 和 adb install -r -t 覆盖，仪器运行使用 adb shell am instrument，不使用 connectedDebugAndroidTest。
- Debug / AndroidTest 构建、21 项相关 JVM 测试和 lintDebug 通过，Lint 为 0 errors / 58 warnings / 1 hint。32 项网页与设置真机回归全部通过；额外 PageMaterialUiTest 的四项通过，一项工具栏尺寸断言失败：期望 48dp / 144px，按内容描述取到内部 40dp / 120px 节点。该断言与 TopBarIconButton 均未修改，本次不扩大修复范围，也不把这一项写成通过。完整输出为 artifacts/webview-ordinary-fixed-build.txt 和 webview-ordinary-fixed-device.txt。
- 已人工查看本机仪器测试生成的深色加载页与加载完成页截图 artifacts/webview-ordinary-dark.png、webview-ordinary-loaded.png，原生顶部中文可读、按钮为普通圆形背景，网页视口位于顶部下方。截图使用离线夹具，不冒充受影响用户的 CDK 或信任等级页面验收。
- 测试前后 crash buffer 内容哈希一致，没有新增原生崩溃；开发版仍为 0.3.1-dev / 3002，firstInstallTime 仍为 2026-10-23 14:55:34，覆盖与测试均未卸载应用或清空数据。npm run verify-notes 通过，新增中文行没有连续问号或替换字符。受影响用户设备与 WebView 版本的闪退是否消失仍需实际复测。

## Consequences

取消网页高级模式会失去网页顶部的模糊与折射效果。固定普通布局只移除已知开关控制的路径，不保证修复系统 WebView 自身或其他全局采样引起的故障；本机通过不代表已复现受影响用户的内核版本。旧偏好键可以留在存储中，但不再读取或写入，避免为移除一个选项重置整份设置。
