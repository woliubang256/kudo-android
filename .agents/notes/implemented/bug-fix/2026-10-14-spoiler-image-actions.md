# Agent Note: 剧透图片揭示后预览与隐藏分开操作

Status: implemented

## Problem

剧透区域清晰后仍拦截整块点击，图片自己的点击也绑定为显隐切换，无法进入大图。用户要求模糊时点击揭示、清晰时点击预览、清晰图片右上角的斜线眼睛按钮才负责恢复模糊；纯文字剧透保持原行为。列表封面仅在接口提供可靠剧透标记时模糊。

## Decision

列表接口没有可靠剧透标记，列表保持现状，不额外读取每帖详情。在含图片的区域清晰时去掉父区域的隐藏点击，图片预览走现有相册覆盖层，但独立预览已揭示的当前剧透图，不混入普通相册或其他隐藏区域。右上角使用 VisibilityOff 图标、32dp 半透明黑色圆底与独立 48dp 点击区域，隐藏动作仍作用于原 spoiler 区域。纯文字区域与签名默认保留原显隐行为。

## Existing note audit

[原剧透行为](../../implemented/feature/2026-10-12-spoiler-content.md)部分被取代，仅图片清晰后的手势改变，隐藏态、纯文字、无障碍遮蔽、旧系统遮挡和普通相册过滤保持；双方互链，不归档。[相册及返回目标](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)复用单窗口覆盖层和来源锚点，不改变普通楼层相册。没有同主题 proposed/rejected 决策或完全取代的笔记。

## Alternatives considered

- 将已揭示图片放入普通楼层相册可以方便与其他图片一起翻页，但相册可能恢复或从普通图片入口打开，从而提前显示隐藏内容；剧透图保持独立预览。
- 继续用整块区域点击隐藏无需额外控件，但与图片放大争抢同一次点击；隐藏移到图片右上角独立按钮。
- 为每张列表封面请求详情能解析正文 spoiler，但增加列表网络和缓存成本，且与用户“不能就不用”的范围不符；没有可靠列表标记时保持现状。

## Testing

真机生产客户端只读获取 `/latest.json`：30 个主题、17 个封面，仅有 image_url/thumbnails；缩略图只有尺寸与 URL，没有 spoiler/blur 标记（`artifacts/spoiler-list-audit.json`）。桌面请求 403 不作为无标记证据。可选审计测试默认 Assume 跳过，不计入功能测试通过数。

新增清晰图片点击预览测试先在旧实现失败（expected 1 but was 0，`artifacts/spoiler-actions-repro-device.txt`）。修复后 37 项 JVM 测试通过，13 项关联真机功能测试通过（runner 显示 14 项，含 1 项跳过的审计；`artifacts/spoiler-actions-regression-device.txt`）。覆盖隐藏点击揭示、清晰点击预览、角标只隐藏、预览返回保持清晰、状态恢复、普通相册与其他剧透隔离、纯文字原行为和强制旧系统遮罩。

Debug 与 AndroidTest 构建成功；Lint 0 errors、45 既有 warnings、1 hint。RMX2202 / Android 13 使用 versionCode=2005、versionName=0.2.3，安装前回读 APK 核对签名一致，只用 `adb install -r` 覆盖应用、`-r -t` 覆盖测试包，保留数据。最终 `artifacts/spoiler-revealed-controls.png` 已实际查看，确认清晰图片右上角斜线眼睛、另一隐藏区域没有按钮；隐藏态截图确认文字、表情与图片均模糊。

## Consequences

图片预览与隐藏各有独立入口，恢复状态不会把其他隐藏图片加入相册。同一 spoiler 包含多张图片时，隐藏按钮仍隐藏整个原区域；独立预览只显示点击的当前图片。不能仅凭 URL 或缩略图名称推断列表封面剧透状态；接口未来增加可靠标记时才能扩展列表模糊。
