# Agent Note: 收藏图库按分类浏览与当前图片返回过渡

Status: implemented

## Problem

图库管理只向查看器传单张图片，没有缩略图锚点；无法横滑同分类，也无法从最后查看的图片缩回网格。图库中的图片可能来自不同帖子，同一原图也可在不同目录重复收藏，不能沿用整组统一来源或按 URL 定位返回项。

## Decision

以点击时当前分类的图片顺序建立独立浏览快照，“全部”包含所有收藏记录，不按 URL 去重。复用全屏查看器的分页、手势和来源过渡，不读取帖子相册。每一页单独传递原帖来源并随会话恢复，明确缺失来源的图片保持空来源。

每个目录网格注册稳定 origin，缩略图记录实际裁切矩形、圆角及 Painter。打开时由点击图片放大；关闭时以浏览快照中的收藏 ID 定位当前页，在惰性网格中滚到对应项并等待布局，再缩回当前图片。返回目标须避开吸顶目录栏；若记录已移除或来源页面失效，沿用查看器的安全淡出，不缩向同 URL 的其他收藏。

逐图来源及保存恢复由相册会话负责；图库网格传入分类快照并注册自己的锚点。编辑器的多选插入保留原行为。缩略图自身使用与锚点一致的 10dp 圆角裁切，转场只隐藏图片区域，保留来源操作行。

## Existing note audit

检索 proposed、implemented、rejected 中图库、来源过渡与 GalleryOrigin：[本地图库](../../implemented/feature/2026-10-29-local-image-library.md)的单图打开决定由本方案部分取代，保留本地存储和账号隔离；[相册会话](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)扩展逐图来源，保留重复项索引和安全关闭；[来源动画](../../implemented/feature/2026-10-09-image-origin-animation.md)与[全屏手势](../../implemented/feature/2026-10-09-image-viewer-motion.md)直接复用。其他图库材质、编辑器与分类排序不受影响；Sentry 提案无关，无需归档旧篇。

## Alternatives considered

- 继续单图打开可避免跨帖子来源混用，但不能横滑；增加逐页来源后以当前分类快照浏览。
- 按 URL 匹配返回目标实现简单，同一原图跨目录收藏时会定位到错误记录；使用收藏 ID 和快照索引。
- 为图库单独建立查看器可隔离改动，但会复制缩放、导出和动画行为；复用既有同窗口查看器和 GalleryOrigin。

## Testing

RMX2202 / Android 13 新增 ImageLibraryGalleryUiTest 五项通过（artifacts/library-gallery-first-device.txt），覆盖分类与全部顺序、从中间项打开、长图翻页、重复 URL 独立来源、离屏定位、真实像素的放大缩回、当前记录被移除后的关闭及状态恢复。重复图的收藏操作新建隔离测试目录，并核对实际写入的来源帖子和楼层。

原图库管理与帖子相册合计 58 项首次回归有两项失败，见 artifacts/library-gallery-regression-device.txt。长按移动用例原先固定期待单图页码 0，按现在分类顺序点击的是第 2 张，更新断言为页码 1 和 2 / 2 后通过；另一条 fullscreenImageLoadingUsesTheTricolorIndicatorUntilDecodeCompletes 仍期待三色圈，但运行时早已由百分比圈替代，属于本地图库旧笔记已记录的不匹配检查，本轮保留失败记录，不声称首次集合全绿。移动用例和补强实际 280dp 宽度断言的深色 130% 字号重复图用例共两项定向复测通过（artifacts/library-gallery-focused-device.txt）。去重后有 62 项相关真机用例通过、1 项既有加载圈旧断言未通过，清单见 artifacts/library-gallery-verification.json。

ImageLibrarySourceTest / ImageGalleryStateTest 共 11 项 JVM 用例通过（artifacts/library-gallery-sources-unit.txt）。Debug / AndroidTest 构建与 lintDebug 通过，见 artifacts/library-gallery-device-build.txt、artifacts/library-gallery-focused-build.txt、artifacts/library-gallery-lint.txt。已目视核对 artifacts/library-gallery-shots/ 中放大、缩回、返回位置及深色窄屏截图，中文与原帖行可读。

测试只使用独立偏好文件和离线图片，没有修改真实图库。签名一致后 adb install -r 覆盖 0.3.5-dev / 3006，firstInstallTime 仍为 2026-10-23 14:55:34；不卸载、不清数据。其他机型和真实 CDN 失效图片未在本轮实机验收。

## Consequences

图库能连续浏览当前分类并缩回当前收藏记录，逐张来源不会串帖。惰性网格返回项需要先组合布局；尺寸、吸顶标题和系统栏恢复会影响坐标，返回定位等待实际布局，仍受既有 1200ms 来源定位超时约束。数据更新后的浏览保留快照，缺失记录降级关闭；Painter 和布局坐标不写入持久状态。网格只为已组合的缩略图保留锚点，离开组合时清理 Painter，避免为整个图库常驻图片引用。
