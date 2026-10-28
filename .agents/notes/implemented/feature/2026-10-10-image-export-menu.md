# Agent Note: 图片长按菜单与原图导出

Status: implemented

## Problem

图片查看器的长按只展示附件信息及浏览器入口。用户要求参考酷安的居中圆角文本菜单，提供系统分享、保存当前原图及多图时保存全部原图。

## Decision

菜单的“保存原图／保存所有图片”文案由[本地收藏图库](2026-10-29-local-image-library.md)调整为“下载图片／下载全部”，正文图片长按复用菜单并加入“收藏图片”；下述原图导出、容量与浏览组协议不变。

菜单使用自定义 Dialog 内容，居中、小圆角、纯文本行，不使用 Material AlertDialog 外观；提供分享图片、下载图片和按浏览组数量决定的下载全部，会话稳定时还提供本地图库的收藏入口。长按时固定当前图片与相册快照，防止异步补齐或翻页改变操作对象。菜单取消只关闭弹窗，翻页和来源返回沿用相册协议。

保存菜单括号内显示原图容量，来源与按需响应头查询由[缺失附件容量读取](../bug-fix/2026-10-13-image-capacity-resolution.md)接管，正文 lightbox 的 sizeLabel 继续作为回退。当前图片显示规范化的 B / KB / MB 等容量，所有图片将长按时浏览组快照内的已知容量相加并加“约”；站点元信息可能舍入，因此总量属于预估。按 1024 换算，兼容大小写、空格和 KiB / MiB 等单位，未知、非法、非正数及超出 Long 范围的容量不参与猜测：单图未知时只显示“保存原图”，批量任一张未知或累加溢出时只显示“保存所有图片”，不展示“容量未知”或空括号。不改变导出对象、顺序或文件内容。

ImageDownloader 使用现有受限图片 HTTP 客户端下载 originalUrl，流式写入临时文件，按文件头判断格式，保留 GIF/APNG/WebP 原始字节；单图最多64 MiB，拒绝把验证 HTML 当图片保存。分享通过仅开放 image_exports 缓存目录的 ImageFileProvider 提供临时只读 content URI，并使用 ACTION_SEND 系统选择器。GalleryImageStore 通过 MediaStore 写入 Pictures/酷DO，Android 10+ 使用 IS_PENDING，失败删除未完成条目；API 26–28 按需请求 WRITE_EXTERNAL_STORAGE，不请求读图库权限。ImageSaver 批量逐张执行，失败继续并显示数量，取消删除当前未完成文件；已经保存的图片保留。重复点击不并发执行。

下载与落盘在工作线程执行，分享启动与 Toast 明确在 Dispatchers.Main.immediate 执行。查看器关闭取消导出协程；分享成功启动后保留文件供接收方读取，后续下载清理24小时前的本功能缓存。LocalImageExportServices 只提供下载/落盘/分享依赖注入，测试复用生产当前页、批量选择及调度逻辑，不向其他应用发送测试图片。

## Existing note audit

[全屏手势](../../implemented/feature/2026-10-09-image-viewer-motion.md)部分重叠，其长按信息弹窗被本篇菜单取代，缩放、返回与过渡继续成立。[相册分组](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)部分重叠，仍以当前楼层或列表主帖的浏览组为边界。[原生架构](../../implemented/architecture/2026-10-07-native-community-client.md)部分重叠，复用按域发送 Cookie 的图片客户端；不改变 Coil 解码。无完全吸收或过时提案。

## Alternatives considered

- 分享图片 URL 无需下载即可打开系统面板，但接收应用得到链接而非图片，不符合分享图片的要求。
- 把预览 Bitmap 压缩成 JPEG 可复用已解码内容，但会丢失原图清晰度、透明度和动画，直接导出原始字节。
- 使用既有底部 Action Sheet 能复用拖动逻辑，但用户给定的是居中圆角菜单，以本次明确参考为准。
- 查询原图响应头可补齐缺失容量，读取实际响应体则能取得准确字节数；前者依赖服务器支持且增加请求，后者会提前下载所有原图。初期仅采用附件信息以避免额外请求；这部分取舍由[原图容量读取](../bug-fix/2026-10-13-image-capacity-resolution.md)替代，只为缺失的当前原图查询 HEAD，继续避免预下载整个相册。

## Verification

2026-10-13：缺失容量时的菜单文字先由 `missingCapacityOmitsParenthesesAndDoesNotHideKnownCurrentCapacity` 在真机复现失败（`artifacts/image-capacity-label-repro-device.txt`），覆盖单图省略容量和批量缺失时保留当前已知容量。修复后 `ImageExportUiTest` 的4项真机测试通过（`artifacts/image-capacity-label-device.txt`），包含原始动画字节导出和失败清理回归。

容量展示沿用用户要求仅编译打包，不运行设备测试；已同步 ImageExportUiTest 与 ImageGalleryUiTest 中受菜单文案影响的断言。本次手工检查用例（未执行）：单图 512 KB 显示“保存原图（512 KB）”并隐藏批量；512 KB 与 1 MB 的两图浏览组显示“保存所有图片（约 1.5 MB）”；任一张缺少或带非法容量时批量显示“容量未知”，当前已知图片仍保留自己的容量；翻页长按显示新当前图片的容量，批量仍汇总同一浏览组；深色 130% 字号下括号内容允许换行且选项可点击。

2026-10-12：Release 与受影响的 AndroidTest Kotlin 编译通过（`:app:assembleRelease :app:compileDebugAndroidTestKotlin -PversionName=0.2.0 -PversionCode=2001`），日志 `artifacts/image-action-capacity-release-build.txt`。正式包保持 0.2.0 / 2001，仅 arm64-v8a，产物为 `artifacts/release/0.2.0/image-action-capacity/kodo_0.2.0_release_arm64-v8a.apk`；没有安装到手机、运行设备用例或移动发布 Tag。

ImageExportTest 覆盖动图原始字节与格式修正、HTML/超限拒绝、下载和写入部分失败继续、取消清理。ImageExportUiTest 验证单图隐藏批量、深色130%字号、可读只读分享 URI、私有缓存不可分享、MediaStore 字节一致与 pending 发布、失败清理；测试条目按返回 URI 或唯一文件名删除，不读取用户照片。

ImageGalleryUiTest 从第3张实际长按，确认保存和分享都下载第3张原图，保存全部按整个浏览组顺序执行，取消菜单保留当前页。首轮30项真机回归出现1项后台 Toast 错误，日志 artifacts/image-actions-first-device.txt；显式主线程约束修复后全部30项通过（52.97秒），见 artifacts/image-actions-final-device.txt。JVM 共135项通过，Lint 0错误、30条原有警告和1条提示，完整构建见 artifacts/image-actions-fixed-build.txt。

已查看浅色实际相册菜单截图 artifacts/gallery-image-action-menu.png 和深色130%字号截图 artifacts/image-menu-dark-large.png，文字及触控区域正常；深色截图中的按压涟漪是点击瞬间状态。Android 13 真机从真实首页帖子打开图片，长按单图只显示两项，选择分享后系统选择器展示正确图片预览；证据为 artifacts/image-actions-live-menu.png、artifacts/image-actions-live-share.png 及同名布局 JSON。只验证选择器及文件读取，不选择接收方、不发送图片；相册落盘通过 MediaStore 测试验证并删除测试创建的条目，不额外保存真实帖子图片。

## Consequences

容量回退到站点附件信息时，元信息舍入可能与实际保存字节数略有差异；总量明确显示预估，信息缺失时不给不完整的合计，也不增加“容量未知”提示。只为缺失容量的当前原图按需查询响应头，不预下载；菜单仍可直接保存缺失容量的图片。

原图导出保留清晰度与动画，不受查看器解码采样影响。下载失败、存储空间不足或旧系统拒绝权限反馈失败；单图超过64 MiB不能导出，未识别格式不能保存。当前真机覆盖 Android 13，API 26–28 的授权分支尚无同版本设备验收。动图字节保留不保证每个系统图库都能播放 APNG 等格式。
