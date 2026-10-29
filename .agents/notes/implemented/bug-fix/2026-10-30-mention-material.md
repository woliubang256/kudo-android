# Agent Note: 提及候选跟随材质并在同窗口绘制

Status: implemented

## Problem

用户发现 @ 候选是固定实色面板，未跟随当前材质设置。原非焦点 Popup 保护了正文焦点、键盘和紧凑回复的可用空间，但独立窗口不能直接复用编辑工具已验证的同窗口背景采样。

## Decision

复用 EditorToolBackdropHost 的按需背景层，增加非模态候选请求；内容和候选为兄弟节点，只有工具或候选存在时才记录来源层。候选不添加遮罩、不隐藏正文语义、不设置焦点锁，点击外部只关闭候选并允许原点击继续。调用者上下文、最新设置、定位和关闭回调跟随原编辑器；退出编辑器必须清理请求。

候选在同窗口中遵循系统模糊兼容边界，保留候选圆角和列表布局。原先沿用模态折射和染色的选择由[普通候选模糊](2026-10-30-mention-candidate-refresh.md)部分取代：用户明确要求此处减少液态厚边缘、增加遮色和模糊；本篇的同窗口采样及非模态契约继续有效。定位继续按窗口坐标避让状态栏与键盘，在宿主放置时扣除宿主原点，不依赖系统重新挪位。无宿主的独立组件保留原 Popup 与实色兼容表面。

关闭状态仅属于当前 TextFieldValue，正文或光标改变后重置；不能长期记住一个已关闭的词段值，否则清空后重新输入同一词段仍会被永久过滤。

## Existing note audit

[提及补全](../../implemented/feature/2026-10-29-editor-mentions.md)部分取代：应用内候选不再使用独立 Popup，其词段、搜索、发布、预览和正文样式契约保留。[编辑工具采样](../../implemented/bug-fix/2026-10-18-editor-tool-sheet-material.md)部分重叠：复用其同窗口兄弟层、按需记录及上下文传递，工具仍保持原有模态隔离；[采样环修复](../../implemented/bug-fix/2026-10-16-directory-material-render-crash.md)的禁止来源包含自身约束继续有效。其他检索命中为图库和工具面板，没有同主题的过时提案或需要归档的记录。

## Alternatives considered

- 直接给 Popup 降低底色透明度改动最小，能看见窗口后方颜色，但没有真实背景模糊和折射，无法满足材质设置。
- 保留 Popup 并跨窗口引用应用 LayerBackdrop 最能保留原焦点行为，但已有编辑工具的真机实验未通过坐标与模糊验证；采用同窗口兄弟层，同时显式维护非模态交互。

## Testing

- 旧实现的动态背景复现用例失败，LIGHT/LIQUID 的红蓝差为 0.0（artifacts/mention-material-red-device.txt）；没有发布或通知。
- 接入后的首轮三项中，浅深色三种材质与条纹模糊两项通过，生命周期用例重新提交相同词段超时（artifacts/mention-material-first-device.txt）。先按测试条件处理，补齐输入区域的 IME 避让并先清空再输入；第二轮仍在重开处失败，证明原 dismissed 值在编辑后没有释放，不能归为夹具问题。改为按当前 TextFieldValue 记忆关闭状态，让同词段重新输入也可查询（artifacts/mention-material-final-device.txt）。
- 32 项相关回归为 31 通过、一项重开状态失败，耗时 64.427 秒（artifacts/mention-material-final-device.txt）。关闭状态修复后，候选材质三项与编辑交互五项全部通过，耗时 25.471 秒（artifacts/mention-material-reopen-device.txt）。另 24 项工具材质、工具栏和编辑生命周期回归已在前批通过，后续只改候选关闭状态，没有重复无关测试。
- 材质三项覆盖浅深色与三种材质的动态红蓝透色、条纹窗口坐标与模糊边界、来源实际父链不包含候选、外部控件点击继续响应、重新输入相同词段和调用者退出释放来源。五项编辑交互改为使用生产宿主，覆盖点选后的正文/光标/键盘、280dp 深色 130% 字号回复、迟到响应、重试/发布锁定、返回与预览。
- 人工查看 artifacts/mention-material-light-liquid.png、mention-material-light-blur.png、mention-material-dark-translucent.png、mention-material-stripes.png，以及实际 EditorForm 的 mentions-topic-light.png 和 mentions-reply-dark-large.png，确认中文可读、输入行无遮挡、候选在键盘上方。背景与用户名为测试夹具，实际模糊与坐标由像素和窗口断言另外验证。
- 浅深色三种材质的截图汇总 artifacts/mention-material-review.png 已目视检查；最终 lintDebug 通过，0 errors、60 warnings、1 hint（artifacts/mention-material-reopen-lint.txt），没有增加警告。
- RMX2202 / Android 13 使用 versionCode=3002、versionName=0.3.1-dev 构建，签名与已安装开发版一致，主包和测试包分别 adb install -r、adb install -r -t；firstInstallTime 仍为 2026-10-23 14:55:34。最终 Debug / AndroidTest 构建通过（artifacts/mention-material-reopen-build.txt）。没有真实发帖、上传、通知或设置写入。

最终候选回归使用 adb shell am instrument -w -r -e class com.kudo.android.EditorMentionMaterialUiTest,com.kudo.android.EditorMentionsUiTest com.kudo.android.debug.test/androidx.test.runner.AndroidJUnitRunner；不使用自动管理安装生命周期的 connectedDebugAndroidTest。本轮只改绘制宿主和关闭状态，沿用上一批已验证的词段、接口及渲染 JVM 结果，没有重复全量测试。

## Consequences

候选能够真实采样背景并随材质变化，同时保持正文输入与外部点击。代价是根部宿主需要同时维护模态工具和非模态候选，区分语义与焦点隔离，并把窗口坐标转换为宿主坐标。其他 Android 版本和 GPU 尚未验证，不能以透明透色或语义节点存在代替真实模糊验收。
