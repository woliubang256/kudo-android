# Agent Note: 按身份保存的本地收藏图库

Status: implemented

## Problem

帖内图片只有查看和导出，不能按目录积累可复用的图片。用户希望从正文或查看器收藏图片，在“我的”页设置下管理，并在发帖、回复时直接复用原图链接；不同账号的数据不能混用。

## Decision

访客及每个用户 ID 分别保存版本化 JSON，数据只包含目录、HTTP(S) 原图和缩略图链接、描述与原帖楼层来源，不保存图片文件，不向服务器写收藏。默认收藏目录始终存在。同一目录相同原图幂等，允许不同目录分别收藏。独立偏好文件的后台读改写受同一 Mutex 保护；失败和不支持的版本报错，不以空图库覆盖原始数据。

图片长按和查看器共用导出菜单并增加收藏；下载全部仍以已有浏览组为边界。来源明确传递到全屏会话，不从临时 UI origin 或图片 CDN 地址猜帖子。未揭开的剧透图片不能通过长按导出或收藏。会话不稳定时不开放写入口，账号变化取消旧弹层。

脚注宿主在账号图库环境外，不能只依赖显示位置的 CompositionLocal。脚注请求携带点击时的组合上下文及明确的帖子来源，根部显示时恢复该上下文；来源账号变化使原脚注关闭，避免遗留旧账号入口。先用 `footnoteImagesCanBeCollectedWithTheirOriginalPostSource` 真机复现缺少“收藏图片”（`artifacts/image-library-edge-repro-device.txt`），再补齐此契约。

Markdown 预览是独立 Dialog 窗口，内部单独提供 EditorToolBackdropHost，使图片收藏目录面板属于预览窗口而不是被它遮挡的 Activity 窗口。`previewImageCollectionUsesThePreviewWindowRatherThanTheCoveredAppWindow` 比较两者实际语义根 ID，先复现 root 2 / root 1 不一致（`artifacts/image-library-preview-window-repro-device.txt`）；不能只靠 isDialog 匹配器，因为同窗口 CommunitySheet 也有 dialog 语义。预览内收藏没有虚构原帖来源，也不改变草稿或触发上传。

收藏使用现有全窗口工具面板选择目录，提供新建并收藏的单次原子更新。只有打开菜单时才创建导出依赖，不为每个正文预先创建下载器。目录模型支持重命名、删除目录并把图片移入默认目录，不删除原图。

“我的”页设置下的图库管理由访客与登录布局同时提供。上方横向目录（含全部）过滤下方自适应惰性图片网格，每项提供具体来源楼层和移除按钮；管理目录支持新建、重命名、删除，删除有确认并保留图片。图片单独进入查看器，避免跨不同帖子浏览组时把第一张来源错误用于其他图片。读取失败显示重试，不把坏数据展示成空图库。

共享 EditorToolbar 的末尾增加图库入口，因此新话题、回复、编辑及私信保持同一行为。面板复用目录网格，多选顺序跨目录保留，同一原图在插入时去重。图片以转义后的 `![描述](https://cdn.example.com/image.png)` 插在选区末尾，保留选中文字、最新草稿和发布流程，不进入上传函数。选择器在键盘隐藏后使用有界高度，避免回复小容器和无界惰性网格测量；发送、上传及会话未稳定时入口禁用。切换身份销毁旧菜单和选择草稿。

管理网格以 148dp 最小单元容纳来源与删除操作；选择器没有这些文字操作，使用 112dp 最小缩略图单元，避免窄屏大字下只露一张占满视口的图片。大字回复的失败记录同时暴露测试错误假设：等待不可见的第二个惰性项不能代表面板未打开；验收使用真实键盘就绪和多缩略图可达条件。

## Existing note audit

[图片导出](../../implemented/feature/2026-10-10-image-export-menu.md)部分重叠，导出权限、原始字节与浏览组快照不变，仅扩展菜单入口；[图片相册](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)部分重叠，保留动画及来源恢复协议，另带帖子来源元数据；[编辑工具栏](../../implemented/feature/2026-10-15-editor-markdown-toolbar.md)部分重叠，保留草稿、光标和发布契约，复用工具面板；[剧透图片](../../implemented/bug-fix/2026-10-14-spoiler-image-actions.md)继续约束未揭开内容；[首页 Tab](../../implemented/feature/2026-10-29-editable-home-tabs.md)仅共享身份分区原则，不共用存储。其余检索命中不相关，没有完全吸收或过时提案。

## Alternatives considered

- 下载原图到私有目录能离线显示且不依赖 CDN 生命周期，但会引入容量、清理和上传路径，违背直接复用线上图片的需求；本轮只存链接。
- Room 能做大规模索引、关系和事务，适合庞大图库；当前仅本地目录及元数据列表，不增加数据库依赖，使用独立偏好文件、后台读写和串行读改写。规模导致整体 JSON 开销明显时再迁移。
- 复用服务器书签能跨设备同步，但书签对象是帖子且没有本轮所需目录图片契约，也不符合纯本地保存；使用独立身份分区。

## Testing

`ImageLibraryTest` 覆盖版本、损坏保护、目录约束、去重、链接安全、来源和删除迁移；`ImageLibrarySourceTest` 覆盖全屏回复来源恢复、旧格式恢复和列表来源回退；`ImageLibraryStoreTest` 使用随机独立测试偏好文件覆盖访客及双账号持久化、40个并发写入、幂等和损坏保护，不触碰真实图库。`LibraryImageInsertionTest` 覆盖原图优先、转义、选择顺序、跨目录去重、反向选区和草稿保留。

66 项定向 JVM 用例通过，涵盖上述模型和既有图片导出、相册变换、Markdown、脚注、编辑草稿（`artifacts/image-library-final-validation.txt`）。Debug 和 AndroidTest APK 构建通过；Lint 为 0 errors / 60 warnings / 1 hint，新增一条存储的 UseKtx 建议，保留 commit 的布尔返回以检查实际写盘结果。

RMX2202 / Android 13 上，首批新增存储 5 项、图库 UI 14 项，以及导出、相册、工具栏、脚注、剧透、访客布局的定向回归共 74 项全部通过（`artifacts/image-library-final-device.txt`）。UI 覆盖正文和全屏当前图片、新建目录收藏、取消不写、目录筛选及管理、移除确认、原帖楼层、多图插入、不发布不上传、窄屏深色大字、真实键盘和换号关闭旧选择器。测试图片与请求为离线夹具，不向真实站点上传或发帖。

独立预览窗口修复后，图库 UI 增至 15 项，与工具栏 10 项、回复预览图片 6 项合计 31 项专项回归全部通过（`artifacts/image-library-preview-fixed-device.txt`）；包含预览内收藏、窗口归属、回到预览及未虚构帖子来源。与前批去重后累计 81 个不同真机用例通过。最终 APK、AndroidTest 构建和 Lint 通过（`artifacts/image-library-preview-fixed-build.txt`），无关已测用例不重复跑全量。

首次扩大到 77 项回归时有 4 项失败、1 项可选联网探针跳过（`artifacts/image-library-regression-device.txt`）。与新增行相关的访客惰性列表定位改用 item 索引；导出选图断言不再绑定下载后可能变化的缓存容量，两个定向复测通过。未修改的两个旧断言分别期待 Debug About 的“检查更新”（当前为“开发版”）及加载圈黄白深色三段（当前为白色百分比圈），最终 74 项定向集合明确排除这两个不匹配断言和可选联网探针，不把整个扩展集报告为全绿。对应复测为 `artifacts/image-library-regression-retest-device.txt`。

已目视确认 `artifacts/image-library-picker-dark-large.png`、`artifacts/image-library-management-dark-large.png` 的中文、目录、选择状态和来源按钮。实际 App 从“我的”页设置下进入图库管理并返回，证据为 `artifacts/image-library-live-profile.png`、`artifacts/image-library-live-management.png`；没有写入真实图库。同签名使用 adb install -r 保数据覆盖安装，版本保持 3002 / 0.3.1-dev，firstInstallTime 仍为 2026-10-23 14:55:34。真实多登录账号切换及其他机型尚未人工验收，账号分区由隔离存储及换号 UI 用例验证。

## Consequences

链接失效、来源删除和权限变化仍可能使图片或原帖不可用；本地收藏不等于离线备份，也不跨设备同步。原图地址可能不是 CDN，不假定任意地址永久有效。元数据整体 JSON 写入随规模增长；所有读写放到 IO 线程并串行，失败保持原数据和可重试入口。
