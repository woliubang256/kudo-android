# Agent Note: 按身份保存的本地收藏图库

Status: implemented

## Problem

帖内图片只有查看和导出，不能按目录积累可复用的图片。用户希望从正文或查看器收藏图片，在“我的”页设置下管理，并在发帖、回复时直接复用原图链接；不同账号的数据不能混用。

## Decision

空目录通过零自身位移的 scrollable 提供纵向嵌套滚动入口，使标题能和有图目录一样收起；不移动空态中心或添加虚假滚动内容。新复现覆盖空目录折叠、横滑目录后继续吸顶、反向展开，以及短网格的窄屏大字号。私信和话题/回复采用相同方向约定，见[个人标签折叠](2026-10-30-personal-tabs-collapse.md)。

访客及每个用户 ID 分别保存版本化 JSON，数据只包含目录、HTTP(S) 原图和缩略图链接、描述与原帖楼层来源，不保存图片文件，不向服务器写收藏。默认收藏目录始终存在。同一目录相同原图幂等，允许不同目录分别收藏。独立偏好文件的后台读改写受同一 Mutex 保护；失败和不支持的版本报错，不以空图库覆盖原始数据。

图片长按和查看器共用导出菜单并增加收藏；下载全部仍以已有浏览组为边界。来源明确传递到全屏会话，不从临时 UI origin 或图片 CDN 地址猜帖子。未揭开的剧透图片不能通过长按导出或收藏。会话不稳定时不开放写入口，账号变化取消旧弹层。

脚注宿主在账号图库环境外，不能只依赖显示位置的 CompositionLocal。脚注请求携带点击时的组合上下文及明确的帖子来源，根部显示时恢复该上下文；来源账号变化使原脚注关闭，避免遗留旧账号入口。先用 `footnoteImagesCanBeCollectedWithTheirOriginalPostSource` 真机复现缺少“收藏图片”（`artifacts/image-library-edge-repro-device.txt`），再补齐此契约。

Markdown 预览是独立 Dialog 窗口，内部单独提供 EditorToolBackdropHost，使图片收藏目录面板属于预览窗口而不是被它遮挡的 Activity 窗口。`previewImageCollectionUsesThePreviewWindowRatherThanTheCoveredAppWindow` 比较两者实际语义根 ID，先复现 root 2 / root 1 不一致（`artifacts/image-library-preview-window-repro-device.txt`）；不能只靠 isDialog 匹配器，因为同窗口 CommunitySheet 也有 dialog 语义。预览内收藏没有虚构原帖来源，也不改变草稿或触发上传。

收藏使用现有全窗口工具面板选择目录，提供新建并收藏的单次原子更新。只有打开菜单时才创建导出依赖，不为每个正文预先创建下载器。目录模型支持重命名、删除目录并把图片移入默认目录，不删除原图。

“我的”页设置下的图库管理由访客与登录布局同时提供。上方横向目录（含全部）过滤下方自适应惰性图片网格，每项提供具体来源楼层和移除按钮；管理目录支持新建、重命名、删除，删除有确认并保留图片。图片单独进入查看器，避免跨不同帖子浏览组时把第一张来源错误用于其他图片。读取失败显示重试，不把坏数据展示成空图库。

管理页和编辑器选择器的目录共用首页 FeedTabBar 文字与黄色渐变指示器，通过按目录 ID 标识的 HorizontalPager 联动点击和左右滑动。每页保留自己的网格滚动位置，选择器的已选图片顺序仍跨目录保留；非当前页不暴露重复图片的操作语义。增删目录按当前目录身份恢复页码，删除当前目录回到全部，不把旧页码错误应用到其他目录。

管理页通过 enter-always 嵌套滚动折叠标题栏，目录 Tab 始终留在状态栏安全区下方，反向滚动展开标题；编辑器选择器不折叠自己的面板标题。Pager 是顶部的独立采样源，网格以实测页头留白而不是裁短视口，页面内容可经过吸顶 Tab 后方。

图库管理页本身提供同窗口 EditorToolBackdropHost，让目录管理、删除目录和移除图片始终从当前页面采样，不依赖根导航是否提供宿主。独立屏幕的缺源降级由 `standaloneLibraryRemovalSamplesThePageRatherThanAnOpaqueFallback` 先复现（`artifacts/library-navigation-repro-device.txt`）；液态、模糊和半透明仍遵循全局设置与系统版本，不强制打开高级材质。

共享 EditorToolbar 的末尾增加图库入口，因此新话题、回复、编辑及私信保持同一行为。面板复用目录网格，多选顺序跨目录保留，同一原图在插入时去重。图片以转义后的 `![描述](https://cdn.example.com/image.png)` 插在选区末尾，保留选中文字、最新草稿和发布流程，不进入上传函数。选择器在键盘隐藏后使用有界高度，避免回复小容器和无界惰性网格测量；发送、上传及会话未稳定时入口禁用。切换身份销毁旧菜单和选择草稿。

管理网格以 148dp 最小单元容纳来源与删除操作；选择器没有这些文字操作，使用 112dp 最小缩略图单元，避免窄屏大字下只露一张占满视口的图片。大字回复的失败记录同时暴露测试错误假设：等待不可见的第二个惰性项不能代表面板未打开；验收使用真实键盘就绪和多缩略图可达条件。

## Existing note audit

[图片导出](../../implemented/feature/2026-10-10-image-export-menu.md)部分重叠，导出权限、原始字节与浏览组快照不变，仅扩展菜单入口；[图片相册](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)部分重叠，保留动画及来源恢复协议，另带帖子来源元数据；[编辑工具栏](../../implemented/feature/2026-10-15-editor-markdown-toolbar.md)部分重叠，保留草稿、光标和发布契约，复用工具面板；[剧透图片](../../implemented/bug-fix/2026-10-14-spoiler-image-actions.md)继续约束未揭开内容；[首页 Tab](../../implemented/feature/2026-10-29-editable-home-tabs.md)仅共享身份分区原则，不共用存储。其余检索命中不相关，没有完全吸收或过时提案。

## Alternatives considered

- 下载原图到私有目录能离线显示且不依赖 CDN 生命周期，但会引入容量、清理和上传路径，违背直接复用线上图片的需求；本轮只存链接。
- Room 能做大规模索引、关系和事务，适合庞大图库；当前仅本地目录及元数据列表，不增加数据库依赖，使用独立偏好文件、后台读写和串行读改写。规模导致整体 JSON 开销明显时再迁移。
- 复用服务器书签能跨设备同步，但书签对象是帖子且没有本轮所需目录图片契约，也不符合纯本地保存；使用独立身份分区。
- 仅在单个网格上拦截水平拖动，代码改动最少，但没有分页过渡且与图片、目录横向滚动竞争；复用首页的 HorizontalPager 和 FeedTabBar，按稳定目录身份组织页面。

## Testing

`ImageLibraryTest` 覆盖版本、损坏保护、目录约束、去重、链接安全、来源和删除迁移；`ImageLibrarySourceTest` 覆盖全屏回复来源恢复、旧格式恢复和列表来源回退；`ImageLibraryStoreTest` 使用随机独立测试偏好文件覆盖访客及双账号持久化、40个并发写入、幂等和损坏保护，不触碰真实图库。`LibraryImageInsertionTest` 覆盖原图优先、转义、选择顺序、跨目录去重、反向选区和草稿保留。

66 项定向 JVM 用例通过，涵盖上述模型和既有图片导出、相册变换、Markdown、脚注、编辑草稿（`artifacts/image-library-final-validation.txt`）。Debug 和 AndroidTest APK 构建通过；Lint 为 0 errors / 60 warnings / 1 hint，新增一条存储的 UseKtx 建议，保留 commit 的布尔返回以检查实际写盘结果。

RMX2202 / Android 13 上，首批新增存储 5 项、图库 UI 14 项，以及导出、相册、工具栏、脚注、剧透、访客布局的定向回归共 74 项全部通过（`artifacts/image-library-final-device.txt`）。UI 覆盖正文和全屏当前图片、新建目录收藏、取消不写、目录筛选及管理、移除确认、原帖楼层、多图插入、不发布不上传、窄屏深色大字、真实键盘和换号关闭旧选择器。测试图片与请求为离线夹具，不向真实站点上传或发帖。

独立预览窗口修复后，图库 UI 增至 15 项，与工具栏 10 项、回复预览图片 6 项合计 31 项专项回归全部通过（`artifacts/image-library-preview-fixed-device.txt`）；包含预览内收藏、窗口归属、回到预览及未虚构帖子来源。与前批去重后累计 81 个不同真机用例通过。最终 APK、AndroidTest 构建和 Lint 通过（`artifacts/image-library-preview-fixed-build.txt`），无关已测用例不重复跑全量。

首次扩大到 77 项回归时有 4 项失败、1 项可选联网探针跳过（`artifacts/image-library-regression-device.txt`）。与新增行相关的访客惰性列表定位改用 item 索引；导出选图断言不再绑定下载后可能变化的缓存容量，两个定向复测通过。未修改的两个旧断言分别期待 Debug About 的“检查更新”（当前为“开发版”）及加载圈黄白深色三段（当前为白色百分比圈），最终 74 项定向集合明确排除这两个不匹配断言和可选联网探针，不把整个扩展集报告为全绿。对应复测为 `artifacts/image-library-regression-retest-device.txt`。

已目视确认 `artifacts/image-library-picker-dark-large.png`、`artifacts/image-library-management-dark-large.png` 的中文、目录、选择状态和来源按钮。实际 App 从“我的”页设置下进入图库管理并返回，证据为 `artifacts/image-library-live-profile.png`、`artifacts/image-library-live-management.png`；没有写入真实图库。同签名使用 adb install -r 保数据覆盖安装，版本保持 3002 / 0.3.1-dev，firstInstallTime 仍为 2026-10-23 14:55:34。真实多登录账号切换及其他机型尚未人工验收，账号分区由隔离存储及换号 UI 用例验证。

目录分页、折叠标题与独立采样宿主落地后，图库 UI 23 项、材质 1 项、普通图标 1 项、编辑工具栏 10 项、首页页头 6 项及搜索 20 项合计 61 项真机回归全部通过（`artifacts/library-navigation-final-device.txt`）。覆盖左右滑动与点击联动、跨目录多选、窄屏深色大字的独立阅读位置、删除当前目录回到全部、换号销毁旧弹层、标题隐藏与反向展开。Debug、AndroidTest 构建及 Lint 通过（`artifacts/library-navigation-final-build.txt`），Lint 仍为 0 errors / 60 warnings / 1 hint。

`ImageLibraryMaterialUiTest` 使用隔离图库及离线纯色图片，在移除面板打开时把蓝色缩略图切成红色；面板标题之前的采样区排除黄色按钮，确认浅深色下液态、模糊及半透明六种组合均跟随页面内容更新。独立页面缺采样源的复现不代表根导航中所有材质问题都只有这一原因。已目视确认 `artifacts/library-removal-DARK-LIQUID.png` 中的背景透出、边缘材质与中文，以及 `artifacts/library-tabs-pinned.png` 中仅目录 Tab 吸顶。

最后仅调整测试宿主和验收断言：图库选择器放在合法 Column 宿主中，普通图标增加浅深色边缘像素与背景一致的检查；重新构建 AndroidTest，并定向重跑这两项及标题折叠，共 3 项通过（`artifacts/library-navigation-focused-build.txt`、`artifacts/library-navigation-focused-device.txt`）。`artifacts/library-home-tabs-picker.png` 确认目录指示器、跨页勾选及插入按钮没有重叠；首页、搜索的 `*-plain.png` 截图确认图标清晰且没有独立圆底。运行时源码未在完整回归后改动，不重复执行无关用例。

空目录手势修复先在旧实现复现：空目录上滑后标题仍显示，短网格的折叠用例原本通过（`artifacts/personal-collapse-red-device.txt`）。修复后 `ImageLibraryUiTest` 25 项全部通过（`artifacts/personal-collapse-device.txt`），覆盖空目录折叠与反向展开、横滑目录后标签吸顶、280dp 深色 130% 字号短网格，以及既有长列表、分页阅读位置、目录管理、收藏插入和账号隔离。已目视检查 `artifacts/library-empty-tabs-pinned.png` 与 `artifacts/library-short-dark-large-pinned.png`，确认中文可读、仅目录标签吸顶；截图来自离线组件夹具，不代表真实网络账号完整验收。

本轮 Debug、AndroidTest 构建与 Lint 通过（`artifacts/personal-collapse-build.txt`、`artifacts/personal-collapse-focused-build.txt`、`artifacts/personal-collapse-final-lint.txt`），Lint 为 0 errors、60 warnings、1 hint。合并私信及话题/回复后的 45 个不同用例中，44 项分批通过，另一个按钮尺寸旧断言在改动前 APK 上同样失败；整体范围、基线对照与设备限制见[个人标签折叠的验证记录](2026-10-30-personal-tabs-collapse.md)。

## Consequences

链接失效、来源删除和权限变化仍可能使图片或原帖不可用；本地收藏不等于离线备份，也不跨设备同步。原图地址可能不是 CDN，不假定任意地址永久有效。元数据整体 JSON 写入随规模增长；所有读写放到 IO 线程并串行，失败保持原数据和可重试入口。

分页与折叠页头需要一起维护目录身份、每页阅读位置及实测页头高度；后续增删目录或调整安全区不能退回仅保存页码或固定留白。材质效果已在当前 Android 13 真机验收，其他 Android 版本及 GPU 的降级效果仍需对应设备确认。
