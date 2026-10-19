# Agent Note: 帖子列表与详情的共享容器转场

Status: implemented

## Problem

帖子详情只从屏幕中央轻微缩放并淡入，不能表达它来自列表中的哪张卡片。用户要求点击后从原卡片展开到全屏，返回时从当前详情收回原卡片，保留列表位置。

## Decision

在 Navigation Compose 2 的 NavHost 外放置 Compose SharedTransitionLayout，复用导航的 AnimatedContentScope，以 sharedBounds 连接列表项和整个详情容器。卡片、标题行、紧凑置顶行、收藏及公开主页条目使用同一来源协议，列表内容和正文交叉淡变，容器边界及圆角随导航过渡变化。搜索将整个命中条目作为来源，摘要、卡片及命中楼层点击都沿用同一来源键。父容器保持列表原位置，ScaleToBounds 使用 FillWidth 和 TopStart，避免正文逐帧重排。详情覆盖层 zIndexInOverlay=1，高于来源默认的 0，防止返回时重建的列表卡覆盖仍在收缩的详情；Host 内保留 TopCenter，使 840dp 限宽页面继续居中。

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

## Manual verification

- 卡片、标题行、紧凑置顶行在打开和返回时均连接原位置，无图帖子同样可用。
- 同一帖子多个来源不串位；详情滚动后返回恢复原列表位置；连续点击不重复导航。
- 直接链接、来源删除及关闭系统动画时可以正常打开和返回；图片相册继续独立工作。
- 进入正文后打开图片预览，先返回图片再返回帖子，检查覆盖层与导航返回顺序。
- 搜索中分别点击同帖不同命中的卡片、摘要及楼层，检查跳转楼层及返回目标；收藏、点赞、公开主页和个人内容重复检查返回位置。

## Testing

用户明确要求不用模拟器，直接修改并打正式包自行验收。本次仅执行 UTF-8 与中文完整性检查、Release 编译和随构建执行的 Lint Vital、笔记校验，以及产物版本、不可调试标记和签名核对；不运行模拟器或设备动效测试。已启动的无窗口模拟器在用户提出要求后停止，未安装应用或测试包。线上 GitHub 最新正式 Release 为 v0.3.0，本地包显式使用 versionName=0.3.0 / versionCode=3001，不打 Tag、不发布。

最终 Release 构建通过，日志为 artifacts/topic-transition-release-final-build.txt；145 篇笔记的目录、格式、相对链接和归档检查通过。scripts/prepare_release.py 校验正式签名、com.kudo.android 包名、0.3.0 / 3001、不可调试标记和唯一 arm64-v8a ABI 后，输出 artifacts/topic-transition/kodo_0.3.0_release_arm64-v8a.apk 及同名 .sha256。APK 为 9,533,802 字节，SHA256 为 c0934ebbfb90da521a29e2229d3879b783e932e378fef2ec90104f46cd7354f9，正式证书 SHA256 为 457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418。这些构建与元数据检查不代表动效已通过真机验收。

## Consequences

共享容器提供无图帖子也适用的双向空间对应关系，每条导航独立保存来源，列表沿用原来的保存状态。代价是动画期间需要保留进出两页的图层，复杂正文和玻璃可能增加开销，不能从编译结果推断真机帧率。返回时来源尚未恢复组合或已被过滤时没有匹配，保留淡变退出。最终手感、裁切和慢网中间帧由用户在真机验收，当前没有设备动效证据。
