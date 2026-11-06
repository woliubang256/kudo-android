# Agent Note: 小程序原生启动进度

Status: implemented

## Problem

沉浸小程序仍在加载、下载、解压和启动时显示官网白色进度界面，与 App 浅深主题不一致。用户要求改用原生进度，同时保留真实阶段和已有权限确认、失败重试。

## Decision

在官网宿主已有加载节点上读取阶段文字、progress 的 value/max、loading/error/hidden 状态及官网权限 dialog。通过已有官方来源消息通道发送启动状态，消息必须匹配当前主 frame、应用 ID 和完整 URL。原生展示官网真实阶段，下载和解压显示各自真实比例，无数值阶段使用等待动画，不拼接虚假的总体百分比。

仅在安全消息接口可用时隐藏官网进度的视觉层并展示原生进度；官网加载器、资源校验、解压和沙箱运行照常执行。权限 dialog 出现时让出原生遮罩，保留网页交互；就绪后显示应用，启动失败显示官网错误和原生重试。无 loader 的登录或验证页继续回退普通网页。普通网页进度保持原行为，右上胶囊在启动期间可操作。

状态绑定完整文档 URL；完整文档导航清空阶段，跨应用或 URL 变化的旧消息不更新显示。JavaScript 对相同快照去重，DOM 属性变化观察限于加载节点和 dialog，避免观察自身适配标记产生反馈循环。页面进度与布局消息可以在后台更新，剪贴板仍要求前台确认。

原生进度采用普通材质，跟随浅深主题，下载和解包使用 LogoYellow 进度条。遮罩阻止点击与无障碍访问底层页面；官网权限 dialog 打开时移除遮罩，保持其原有交互。原生重试重新加载当前完整 URL，保留原 WebView 与原生来源，不复制官网的加载、解压或权限逻辑。

已识别的剪贴板权限改由[单次原生确认](2026-11-05-mini-app-native-clipboard-consent.md)显示于外层原生面板，不要求官网 dialog 实际 open；[音频会话确认](2026-11-06-mini-app-native-audio-consent.md)同样由原生面板显示，只将用户选择交回官网按钮。其他权限仍沿用上述让位机制。

## Existing note audit

[小程序沉浸运行视图](2026-11-04-mini-app-immersive-runtime.md)部分重叠：本篇接管运行时进度的原生呈现，保留其稳定 WebView、安全区域、iframe 几何和回退规则。[网页容器](2026-11-04-mini-app-square.md)保留 H5、关于、权限与生命周期约束，资料字段和宫格菜单在原笔记更新。未发现同主题 proposed/rejected 记录。

## Alternatives considered

- 只用 CSS 换官网进度颜色最少侵入、无需跨层状态，但用户明确要求原生界面，且错误和主题样式仍受官网模板限制。
- 自行下载、解压 ZIP 可完全控制进度，但需要重建包验证、鉴权和 SDK 启动协议，超出展示调整范围并破坏复用官网运行时的约束。
- 按时间模拟总体百分比能让动画连续，但无法对应实际网络与解压工作；保留分阶段真实进度，无数值阶段使用不确定动画。

## Testing

- 新增原生阶段用例先在旧实现复现失败，见 `artifacts/mini-app-native-progress-repro-device.txt`。MiniAppLaunchStateTest 的 3 项用例覆盖真实比例、不确定阶段、权限与就绪让位、错误重试信息，以及旧 URL、其他应用和无效消息拒绝；与 MiniAppInfoTest 共 8 项 JVM 用例通过，见 `artifacts/mini-app-native-progress-build.txt`。
- MiniAppWebUiTest 使用真实 WebView 与受控官网 DOM，验证下载 20% → 70%、解包 75%、无数值启动和就绪、官网权限点击、启动错误重试、同一 WebView，以及浅深主题 × 三种材质。首轮 14 项有 1 项在浏览器尚未处理点击时立即读取结果，见 `artifacts/mini-app-native-progress-device.txt`；等待真实点击结果后通过，不修改运行时代码。
- 相关普通网页回归首轮 17 项有 1 项外部链接计数过早断言，其他 16 项通过，见 `artifacts/mini-app-native-progress-final-device.txt`。同一用例单独复跑通过（`artifacts/mini-app-native-progress-handoff-rerun.txt`）；补充等待真实外部打开回调后，14 项小程序与该项网页用例合计 15 项通过（24.875 秒），见 `artifacts/mini-app-native-progress-final-fixed-device.txt`。两轮合计覆盖 30 项不同的真机用例，包括普通网页 14 项、材质布局 1 项和线上探针 1 项。
- 线上探针访问东北雨姐galgame 和 2048，两者均运行到已就绪、iframe 铺满视口、关于与关闭返回正常。实际捕获正在加载、检查权限、下载应用包（3.46%）、解包文件（100%）、启动应用和已就绪阶段，原生标记均启用；记录为 `artifacts/mini-app-immersive-evidence.json`，原生解包截图为 `artifacts/mini-app-live-launch-0-3.png`。权限让位和失败界面截图为 `artifacts/mini-app-launch-permission.png`、`artifacts/mini-app-launch-error.png`，浅深启动及宫格图也已人工检查中文可读。
- Debug / AndroidTest 构建、JavaScript 语法检查与 UTF-8 乱码检查通过；Lint 为 0 errors / 68 warnings / 1 hint，与改动前一致，见 `artifacts/mini-app-native-progress-lint.txt`。最后仅调整测试等待并再次构建测试包，见 `artifacts/mini-app-native-progress-final-test-build.txt`。
- 设备为 RMX2202 / Android 13，WebView 156.0.8078.47。构建参数和已安装开发版保持 0.3.1-dev / 3002，签名一致，使用 adb 覆盖安装及手动 instrument；首次安装时间保持 2026-10-23 14:55:34，保留登录数据。

## Consequences

官网加载、下载、解包与启动阶段获得与原生主题一致的呈现，进度仍来自真实工作量，权限和沙箱实现继续由官网维护。原生仅增加展示快照和遮罩，不引入另一套包管理或运行时。

官网加载节点、阶段文字和属性仍是适配依赖，结构变化时需要重新验收；没有安全消息接口的内核保留网页进度。线上网络过快可能跳过部分阶段截图，分阶段更新精度由受控夹具确认。单机覆盖不等于其他 WebView 版本已验收；登录后的账号资料和业务网络授权未在本轮实际授权，受控 dialog 点击也不能替代这些流程。
