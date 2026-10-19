# Agent Note: 帖子列表与详情的共享容器转场

Status: implemented

## Problem

帖子详情只从屏幕中央轻微缩放并淡入，不能表达它来自列表中的哪张卡片。用户要求点击后从原卡片展开到全屏，返回时从当前详情收回原卡片，保留列表位置。

## Decision

来源身份、回栈和 headerHeight 保存约束继续有效；下述 sharedBounds 绘制决定由[整卡画面连续性](../../implemented/bug-fix/2026-10-20-topic-card-content-continuity.md)部分取代。保留原决定及其取舍，后续验证和产物以新篇为准。

在 Navigation Compose 2 的 NavHost 外放置 Compose SharedTransitionLayout，复用导航的 AnimatedContentScope，以 sharedBounds 连接列表项和整个详情容器。卡片、标题行、紧凑置顶行、收藏及公开主页条目使用同一来源协议，列表内容和正文交叉淡变，容器边界及圆角随导航过渡变化。搜索将整个命中条目作为来源，摘要、卡片及命中楼层点击都沿用同一来源键。父容器保持列表原位置。共享边界采用 RemeasureToBounds，来源在共享节点内绘制随边界增长的连续不透明底色；内部布局用 skipToLookaheadSize 固定测量，仅内部内容交叉淡变。匹配导航不再叠加整页淡变，避免底色与内容一同消失，只剩列表轻微位移。边界、圆角与底色统一使用 480ms，来源内容退出保留 300ms，给真机复杂页面更多可辨认的中间帧。未匹配的列表项不改变底色、圆角或透明度，避免整张列表一起闪动和无关项逐帧重绘。详情覆盖层 zIndexInOverlay=1，高于来源默认的 0，防止返回时重建的列表卡覆盖仍在收缩的详情；Host 内保留 TopCenter，使 840dp 限宽页面继续居中。

首页、分类、标签、搜索及共用 MaterialPageScaffold 的已测量 headerHeight 使用 rememberSaveable 随回栈保存，返回第一次测量就带上顶部留白，不能先以 0 作为共享目标再在后续帧补上。窗口或字号变化后的新尺寸仍以 onSizeChanged 实测更新；本次回归针对同窗口返回，不声称跨窗口首帧零偏差。

每个列表项实例以 rememberSaveable 保存独立来源键；点击时仅同步传给本次新建的详情回栈项，来源键、来源回栈项和圆角存入该项的 SavedStateHandle。相同帖子在多个分页、搜索楼层或多层回栈中出现时不得互相匹配。只有当前列表项可发起导航，连续点击不能创建重复详情。返回沿用导航的实际回栈与原有滚动状态，不重新请求列表或强行滚回顶部。没有来源或来源已移除时允许普通淡变。

图片相册继续使用既有根覆盖层和图片来源定位，不把其手势、索引同步或窗口沉浸协议迁入帖子导航。

## Existing note audit

- [酷安密度与过渡](../../implemented/feature/2026-10-07-coolapk-density-and-motion.md)部分重叠：只替代有列表来源的帖子淡变缩放，其余密度和导航决定保留。
- [图片来源动画](../../implemented/feature/2026-10-09-image-origin-animation.md)与[图片相册](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)部分重叠：沿用来源失效可关闭、状态不保存布局对象的原则；图片仍使用独立覆盖层，不改变旧决定。
- [首页标签与吸顶](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)部分重叠：保留各分页的列表状态和预组合，不以帖子 ID 作为唯一共享键。
- [公开主页吸顶材质](../../implemented/feature/2026-10-16-public-profile-pinned-material.md)保留每个分页的滚动状态和兄弟采样层；本篇仅给已有条目接入导航来源。未找到同主题 proposed / rejected 笔记，没有完全吸收或需归档的决定。

## Alternatives considered

- 手动截取卡片图层并仿照图片相册绘制根覆盖层：可以精确控制裁切和每帧位置；帖子包含可变文本、工具栏及玻璃采样，还需另管导航返回与生命周期，采用导航已有动画作用域和共享容器。
- 只共享封面图片或标题：元素对应关系清晰，且改动范围较小；无图帖子和整张卡片到全屏的空间变化仍然缺失，采用整个容器，并让内部内容交叉淡变。
- 对整个容器使用 ScaleToBounds(FillWidth) 并淡变：可以在固定布局下保持文字比例；接近屏幕宽度的卡片几乎不缩放，原卡底色保持原高度、目标底色又随内容淡入，真机主要呈现整页替换。改为边界连续绘制底色，固定测量仅用于内部内容。

## Manual verification

- 卡片、标题行、紧凑置顶行在打开和返回时均连接原位置，无图帖子同样可用。
- 同一帖子多个来源不串位；详情滚动后返回恢复原列表位置；连续点击不重复导航。
- 直接链接、来源删除及关闭系统动画时可以正常打开和返回；图片相册继续独立工作。
- 进入正文后打开图片预览，先返回图片再返回帖子，检查覆盖层与导航返回顺序。
- 搜索中分别点击同帖不同命中的卡片、摘要及楼层，检查跳转楼层及返回目标；收藏、点赞、公开主页和个人内容重复检查返回位置。

## Prior implementation verification

2026-10-19 用户连接 RMX2202 / Android 13 后，先核对已安装 0.2.10-dev / 2023 和证书，再以相同 Debug 证书覆盖安装显式 0.3.0-dev / 3001，保留原有数据。真实帖子录屏 artifacts/topic-transition-baseline.mp4 复现进入和返回主要表现为整页淡变，没有明显的卡片展开；逐帧拼图为 topic-transition-baseline-opening.png 与 topic-transition-baseline-closing.png。新增 TopicTransitionUiTest 用真实 NavHost、LazyColumn 和生产转场容器检查进出中间帧像素范围及邻项位置，打开测试先失败。临时探针确认真实导航的来源键与共享状态匹配，打开边界从原卡片到全屏；失败原因不是无图或缺少共享键，而是底色及内容淡变方式。探针同时发现首页返回目标的顶部由 365px 暂时变成 24px。生产 FeedScreen 回归断言首次放置，修复前实际为 24px、期望 258px；rememberSaveable 修复后通过。带共享锚点的未选中邻项像素测试也先复现一同淡出，再限制透明度只作用于匹配卡片。探针不保留在发布代码中。

最终三项设备回归覆盖进入的非全屏不透明中间边界、邻项位置与颜色不变、返回收缩及原位置恢复、首页返回首帧留白，均通过；日志为 artifacts/topic-transition-regression-device.txt。测试包使用 adb install -r -t 显式覆盖，am instrument 运行，不使用 connectedDebugAndroidTest，不卸载或清数据。真实帖子同时检查无图和带图卡片以及正文滚动后的返回；这是视觉核对，不是帧率基准。慢网时详情内容仍可能先显示加载状态，容器不能依赖正文请求完成才出现。

初次交付时用户明确要求不用模拟器，直接修改并打正式包自行验收，因此当时只执行 UTF-8、Release 编译与 Lint Vital、笔记及产物元数据校验；设备测试是在用户随后连接真机并授权查看效果后补上。线上 GitHub 最新正式 Release 为 v0.3.0，本地正式包显式使用 versionName=0.3.0 / versionCode=3001，不打 Tag、不发布。

最终 Release 构建通过，日志为 artifacts/topic-transition-release-final-build.txt；145 篇笔记的目录、格式、相对链接和归档检查通过。scripts/prepare_release.py 校验正式签名、com.kudo.android 包名、0.3.0 / 3001、不可调试标记和唯一 arm64-v8a ABI 后，输出 artifacts/topic-transition/kodo_0.3.0_release_arm64-v8a.apk 及同名 .sha256。APK 为 9,533,802 字节，SHA256 为 3df687ddcd1ad889fd9079b5a2680b41d0462cf344ab8a523c78cafc69aa48d6，正式证书 SHA256 为 457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418。

真机原来是开发签名，正式签名包不得覆盖它。另从同一正式产物仅重签生成 kodo_0.3.0_device_preserve-data.apk，核对其运行时 ZIP 内容与正式包完全相同、证书与已安装开发版一致后 adb install -r 覆盖，保留原 firstInstallTime=2026-10-17 01:33:30。该包不可调试，使用与正式包相同的 R8 优化代码；最终真机录屏为 artifacts/topic-transition-verified-release.mp4。正式包和真机保留数据包分别交付，不要求卸载换签名。

## Consequences

共享容器提供无图帖子也适用的双向空间对应关系，每条导航独立保存来源，列表沿用原来的保存状态。代价是动画期间需要保留进出两页的图层，复杂正文和玻璃可能增加开销，不能从编译或设备断言结果推断帧率。返回时来源尚未恢复组合或已被过滤时没有匹配，保留淡变退出。当前已有同机复现录屏、像素与布局回归；没有跨设备性能、窗口变化和全部来源页面的逐帧验收。
