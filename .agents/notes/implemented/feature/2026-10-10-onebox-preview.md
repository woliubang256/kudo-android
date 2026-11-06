# Agent Note: 外链 onebox 原生预览

Status: implemented

## Problem

网站已生成的 GitHub onebox 包含标题、摘要和缩略图，客户端只保留 header 中的域名链接，丢失预览信息。

## Decision

从 cooked 的 onebox 提取已提供的目标链接、网站来源、标题、摘要与 HTTPS 缩略图，原生显示整块可点击预览。只使用网站响应，不额外抓取外站页面，也不执行嵌入脚本。预览缩略图不进入正文相册，点击仍交给既有站内/网页分流。字段缺失时保留可用标题或链接，没有 metadata 的普通链接仍显示文字。

精确的官方 HTTPS `/apps/<UUID>` 嵌入链接显示紧凑的原生小程序卡片，仅保留图标和名字。名称与图标来自 `/mini-app-api/public/<UUID>/info`，校验响应 ID、非空名称和无凭据 HTTPS 图标，不访问红包等业务接口。公开元信息按应用 ID 在内存共享和合并请求，最多缓存 64 项，成功保留五分钟、失败十五秒后可重试；离开所属环境取消请求，不落盘。加载或失败时保留“小程序”和图标占位，整张卡片始终可点击。目标 URL 独立保存，保留全部 query/hash（包括 `hb`），由[沉浸运行视图](2026-11-04-mini-app-immersive-runtime.md)继续使用官网运行时打开。目录、非官方来源、异常端口和普通媒体不扩大为小程序入口。

正文中的普通文字链接也使用相同的精确应用路径识别，不以 embed_mode 是否存在或取值为条件。解析时把链接提取成既有 Embed 块，在原位置显示同一原生卡片，并保留前后文字、格式与各次链接独立的完整 URL。引用、列表、折叠和剧透沿用所属容器；带图片的链接保留图片，代码示例不变为卡片。普通外链与应用目录仍显示原文字。

## Existing note audit

检索 onebox、外链、预览。[引用分组](../../implemented/bug-fix/2026-10-10-discourse-quotes.md)的 onebox 不当引用契约保留；[网页浏览](../../implemented/feature/2026-10-10-in-app-web-links.md)继续负责点击跳转；[折叠](../../implemented/bug-fix/2026-10-10-discourse-details.md)可包含预览。均为部分重叠，无需归档。

## Alternatives considered

- 客户端抓取所有外链的 OpenGraph 可覆盖没有 onebox 的网址，但滚动会触发额外跨站请求，元信息也与网站缓存不一致，本次复用网站现有信息。
- 原样嵌入 onebox HTML 最接近网页，但需要为每个链接创建 WebView，采用简单原生文字和图片卡片。
- 在正文直接嵌入红包运行页能接近官网的交互，但需要每个楼层创建独立 WebView 并承担启动与权限生命周期；使用原生入口卡片，点击后复用已有小程序容器。
- 只把独占段落的小程序网址变为卡片能减少正文切分，但真实帖子常写“游玩小程序：链接”；在段落内部按原顺序提取链接，并保留周边格式，能覆盖这类分享结构。

## Testing

MiniAppArticleUiTest 在运行时修改前复现官方 iframe 仅出现“打开媒体网页”、没有小程序卡片，记录为 `artifacts/redpack-mini-app-repro-device.txt`。修复后 4 项真机测试通过，覆盖加载中点击、名称更新、同一应用不同红包参数、失败占位、普通媒体与伪造来源回退，以及进入沉浸容器后关闭回正文，记录为 `artifacts/redpack-mini-app-final-device.txt`。既有 MiniAppWebUiTest 与 QuoteContentUiTest 的 24 项回归通过，记录为 `artifacts/redpack-mini-app-fixed-device.txt`；该记录中 2 项新测试因测试树合并和 query 顺序断言失败，调整为未合并节点与参数值校验后，新测试全部通过，没有修改运行时行为来迁就断言。

MiniAppPreviewTest、OneboxPreviewTest、MiniAppInfoTest 和 AppSquareTest 共 14 项 JVM 测试通过，覆盖官方公开 GET 路径、响应身份、图标过滤、请求合并与单个等待者取消、缓存到期、错误退避、超时和环境销毁，以及 iframe 实体解码、红包 query/hash 与原目录契约。构建记录为 `artifacts/redpack-mini-app-fixed-build.txt`。

显式启用 `redpackPreviewLive=true` 后，RedpackArticleLiveProbeTest 使用真实公开 SVG 和官方小程序信息，在 RMX2202 / Android 13 上验证两种主题的渲染与点击地址，记录为 `artifacts/redpack-public-assets-live-device.txt`。`artifacts/redpack-live-light.png`、`artifacts/redpack-live-dark.png` 已检查：真实红包图片、应用图标和中文名字可读。此探针不访问真实红包详情或领取接口，小程序启动与返回使用离线夹具验证，不能视为线上领取流程验收。设备版本保持 0.3.1-dev / 3002，首次安装时间仍为 2026-10-23 14:55:34。

Debug / AndroidTest 构建与 Lint 通过，最终记录为 `artifacts/redpack-final-validation-build.txt`。`npm run verify-notes` 通过 169 篇笔记，记录为 `artifacts/redpack-notes-verification.txt`；改动的 UTF-8 中文与 diff 空白检查通过。

本轮42项相关JVM测试全过，其中 OneboxPreviewTest 的2项使用实际 GitHub onebox 结构验证目标、标题、摘要、图片、来源与相册排除，并覆盖非法地址和缺字段回退（artifacts/content-about-checks.txt）。RMX2202 / Android13 的 QuoteContentUiTest 验证暗色130%字号下文字、缩略图与点击目的地通过（artifacts/content-about-fixed-device.txt），已查看 onebox-dark-large.png。Debug构建通过，Lint为0错误、30项既有警告和1项提示。

普通小程序链接先用 MiniAppLinkArticleTest 的 5 项用例复现，其中 3 项在旧实现失败（artifacts/mini-app-plain-repro-unit.txt）。修复后，包含正文、引用、剧透、折叠、列表、picture 与小程序路径的 68 项 JVM 用例通过（artifacts/mini-app-plain-fixed-unit.txt）；覆盖 embed_mode 缺失／true／false、相对链接、前后文字格式、不同启动参数、非法地址、代码与图片链接保留。

12 项真机用例通过（artifacts/mini-app-plain-device.txt），包含新普通链接的两主题卡片、图标与名称、同一应用的请求合并与不同 URL 点击，以及旧 iframe、沉浸运行与关闭、元数据失败、正文普通链接、链接图片及菜单。真实话题 3134492 的普通链接显示服务端返回的“每日 Bingo”和应用图标，原红包 SVG 与后续正文仍完整；探针仅读取帖子／公开信息／图片，点击只捕获回调，不启动真实游戏或领取红包。已查看 artifacts/mini-app-plain-topic-light.png、mini-app-plain-topic-dark.png 与 mini-app-plain-light.png、mini-app-plain-dark.png，身份与解码报告为 mini-app-plain-topic-contract.json。构建显式保持 0.3.5-dev / 3006，验签后覆盖安装，未清除真机数据。

## Consequences

除精确的小程序链接外，无法为网站没有生成预览的普通链接补出图片和摘要；远端缩略图失败仍保留文字。小程序卡片新增对官方公开信息格式的依赖，接口不可用时保留通用入口；缓存不会刷新仍停留在屏幕上的卡片，卡片离开组合后重新进入时才按到期规则重新读取。红包余额、份数、领取权限等业务状态由目标网页或小程序负责。普通链接转卡片后会占用独立段落空间，周边文字仍按来源顺序展示；代码、图片链接和表格单元格继续保留原表现。
