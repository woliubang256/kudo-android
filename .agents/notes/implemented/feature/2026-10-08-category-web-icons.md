# Agent Note: 分类页使用官网图标并移除顶部说明

Status: implemented

## Problem

分类页用统一的井号占位，丢失官网为各分类设置的视觉标识。“发现社区”、独立刷新按钮和“找到你的同好”占用顶部空间，与已选择的底栏分类入口重复。用户确认删除这三处，保留卡片介绍及跳转。

## Decision

分类 icon 元数据和矢量目录独立于页面排版，便于随官网字段和图标库更新。

沿用 /site.json 的分类请求，读取 icon 字段，不根据分类名字或 id 猜图标。官网目前用23种 Font Awesome Free 图标；使用已核对的 SVG path 和 viewBox 构建原生 ImageVector，按需缓存，沿用分类颜色与现有45dp图标底面。资源随 APK 分发，不引入运行时网页或额外图标网络请求。空、null、非字符串及未收录图标回退现有 Tag 图标；保留 Fonticons 署名和 CC BY 4.0 许可。

删除分类页标题行及副标题，列表直接排在状态栏安全区下。卡片标题、简介、跳转和底栏避让保持原契约；加载失败时仍提供现有重试/登录入口，删除的是独立常驻刷新按钮。

## Related decisions

[类别与标签目录](2026-10-15-community-directory.md)部分取代本篇的“无顶部区域”：新增类别/标签 Tab，并按网页侧栏配置读取显示项与排序。本篇的图标来源、卡片内容、状态栏和底栏避让仍保留；原删除独立说明与刷新按钮的理由不变。

[主题展示](2026-10-09-topic-presentation.md)复用分类图标进入作者元信息，扩充42个标签需要的官方矢量，目录合计65种；分类本身仍按服务器 icon 字段索引，不根据名称猜测。

检索分类、category、Category：与[请求数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)部分重叠，补充已有分类模型字段；与[首页标签](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)及[会话筛选](../../implemented/feature/2026-10-08-session-feed-filters.md)仅共享分类入口，不改变筛选或登录规则；液态玻璃及密度笔记为背景，不修改底栏材质；[首页推送](../../implemented/feature/2026-10-08-home-live-refresh.md)中的刷新专指首页，本篇仅移除分类常驻按钮。没有完全吸收或过时提案。

## Alternatives considered

- 使用分类 uploaded_logo 图片：接口已有地址且能自动跟随网站换图，但它是分类页的大尺寸Logo，与网页分类名称前的小图标不同；本次采用 icon 字段对应的图形。
- 用现有 Material 图标近似替代：无需增加资源，但幼苗、资源分享和阅读等轮廓会与官网不同；使用官网实际矢量路径。
- 每次下载网页 SVG sprite：新图标能自动覆盖，但增加启动网络依赖和整站资源解析；23种已使用图标内置，出现新名字时明确回退并可更新目录。

## Testing

CategoryMetadataTest 的2项测试先复现原解析丢失 icon 字段，再通过：两种分类JSON结构、服务端换图标、颜色和简介保留、缺失/null/非字符串/空白字段。日志为 artifacts/category-metadata-repro.txt / category-metadata-checks.txt。浏览器实际分类数据、图形分别保存在 category-web-metadata.json / category-web-glyphs.json；23种 path 和 viewBox 逐一与 Font Awesome Free 6.7.2 官方 SVG 比对相同。

完整37项JVM测试和37项RMX2202 / Android13界面测试通过（79.933秒），Debug/测试包构建成功，Lint为0 errors / 27 warnings，笔记门禁通过。日志为 categories-final-build.txt / categories-final-instrumentation.txt。新增2项界面测试遍历全部23种图标及默认图标，检查分类入口唯一且回调正确、首行在状态栏安全区下、标题区移除、失败重试和验证入口、深色130%及280dp宽窗口；既有设置模态回归覆盖加长的许可说明。

已查看 categories-icons-light.png / categories-icons-dark-large.png。长简介仍按原规则最多两行，窄屏用省略号；图标保留原始比例。真实分类页截图 categories-real-top.png 显示代码、幼苗、分享、硬盘、书本、公文包等，独立标题和刷新按钮不存在；点击开发调优后真实分类列表成功加载，再返回分类页，布局记录 categories-real-open.json / categories-real-loaded.json。

Fonticons署名和许可入口显示在开源致谢，19,484字节的完整许可确认进入 assets/licenses/fontawesome-icons-LICENSE.txt。分发包保留数据覆盖安装，构建/分发/设备 APK 的 SHA256 均为 36ea572348457d2ed16e44f2c1e2f02302ad3a2ee5a1cf836b56950702f8d4d1，记录 categories-apk-verification.txt。

## Consequences

官网更换为未收录图标时显示默认图标，需要更新矢量目录；图标名称随接口实时读取，但图形目录不会远程自动更新。分类卡片下方内容较多时仍需滚动，底栏避让不可随标题删除一起减掉。
