# Agent Note: 正文 picture 图片来源解析

Status: implemented

## Problem

用户反馈话题 3134492 首段后的红包卡片在原生正文中消失。hb.lafi.sh 的公开分享生成器输出带链接的 picture，SVG 地址仅位于 source.srcset，img 保留宽高和说明但没有 src。当前正文仅在 img.src 有效时创建图片块，因此整张卡片被省略。

补全来源后的真实图片探针还暴露 AndroidSVG 1.4 的文字边界问题：居中渐变文字包含 tspan 时，测量宽度先访问尚未计算的文字 boundingBox，触发 makeLinearGradient 空指针。实际话题和最小离线夹具均已在真机复现，不能只验证图片块存在。

## Decision

沿用现有原生图片加载器和图片块，只扩展图片来源选择。picture 内按文档顺序选择 img 之前第一个可用、无条件且格式受支持的 source.srcset，在该来源内沿用密度／宽度候选选择；没有可用来源时回退 img.src/srcset。source 的地址与 img.src 使用同一 HTTPS 媒体地址校验，不执行外站 HTML 或脚本。原图地址、尺寸、说明、外链点击、lightbox 原图与动图行为继续由现有图片契约处理。

带 media 条件的 source 不做浏览器媒体查询求值，除空值、all 和 screen 外回退后续来源或 img；普通正文解析没有窗口和主题上下文，不能默认为条件成立。图片格式限定现有加载器支持的常用图片 MIME，空 type 允许内容识别，不把未知格式优先于有效后备图片。

SVG 的文字边界预计算沿用[原生 SVG 解码决定](../../implemented/bug-fix/2026-10-13-article-svg-decoder.md)：仅对含文字子节点且使用引用画笔的文档，解码阶段临时用纯色画笔测量并恢复，实际输出继续使用完整原始渐变；图片加载、请求和菜单不引入另一路径。

## Existing note audit

检索活跃笔记中的 picture、srcset、红包与链接图片，未发现同主题提案或被否方案。[站内链接与图片](../../implemented/feature/2026-10-09-linuxdo-links.md)部分重叠，保留链接图片点击与相册操作契约；[静态 SVG 解码](../../implemented/bug-fix/2026-10-13-article-svg-decoder.md)部分重叠，继续使用同一 Coil 加载器；[外链预览](../../implemented/feature/2026-10-10-onebox-preview.md)仅为背景，不改变 onebox 或小程序卡片。

## Alternatives considered

- 按红包站域名补专用卡片能立即匹配当前样式，但图片本身已经由外站 SVG 提供，问题在标准 picture 结构；修通用来源选择能覆盖相同格式的其他站点。
- 用 WebView 显示整段分享 HTML 能直接复用浏览器 source 选择，但会引入正文网页实例和生命周期成本；现有原生 SVG 解码器已经足够显示卡片。
- 把所有 source 候选混在一起挑最大图能减少选择逻辑，但会混淆格式优先级和媒体条件；保留来源顺序，只在同一来源内比较清晰度。

## Testing

修复前先写 6 项 PictureArticleTest，其中 5 项复现图片被丢弃或来源未生效（artifacts/picture-source-repro-unit.txt）；随后真机使用已安装账号通过生产客户端读取 /t/3134492.json，确认 pictures=1、sourceOnlyImages=1、parsedImages=0，并在图片块断言处失败（artifacts/picture-source-repro-device.txt）。补来源后真实探针进一步定位 SVG 渐变文字解码空指针，最小离线夹具同样复现（artifacts/picture-source-live-diagnostic-device.txt、picture-svg-repro-device.txt）。未为真实图加载失败放宽断言。

最终 50 项 JVM 用例全部通过，包括 6 项新来源用例及正文、引用、剧透、onebox 回归（artifacts/picture-source-final-checks.txt）。20 项真机用例全部通过（artifacts/picture-svg-final-device.txt）：含真实话题 cooked 与 SVG 的浅深色原生正文、原链接回调、source-only 图片及长按菜单、普通图／lightbox、加载重试、AVIF 动画生命周期、小程序卡片，以及 SVG 的嵌套形状、实际 CDN、相册缩放、渐变文字与内联／请求 CSS。解析报告更新为 parsedImages=1、imageLoadError=null（artifacts/picture-topic-contract.json）。

已人工查看真实内容截图 artifacts/picture-topic-LIGHT.png、picture-topic-DARK.png 与离线夹具 picture-redpack-light.png、picture-redpack-dark.png；真实卡片后的小程序链接、说明和 Bingo 代码块均保留。源 SVG 的部分现代 CSS 颜色仍超出 AndroidSVG 1.4 能力，例如发送者小字颜色存在差异，不声称浏览器逐像素一致。

初轮离线图片 UI 用例使用了段落序号而非图片序号的定位标签，修正测试定位后通过，未据此修改产品逻辑。旧 RedpackArticleLiveProbeTest 在等待固定的小程序名称时 30 秒超时，原因未确认，未计入上述 20 项通过用例；其元数据逻辑不在本次改动范围，相关 4 项离线小程序用例通过。

Debug 主包与测试包显式保持 0.3.5-dev / 3006，设备 RMX2202 / Android 13（1618704c）与新包证书 SHA-256 均为 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f。仅使用 adb install -r（测试包加 -t）及手动 am instrument，不清数据、不使用浏览器或模拟器，不触发发布。

主包与测试包构建通过（artifacts/picture-svg-final-build.txt）；最终 Lint 为 0 错误、71 警告、1 提示，与本次改动前的报告计数一致。UTF-8、git diff --check 及 npm run verify-notes 均通过；覆盖安装后 firstInstallTime 仍为 2026-10-23 14:55:34。

## Consequences

标准 picture 分享卡片无需按外站域名做特例，来源、原链接、尺寸、说明和图片菜单沿用既有契约。来源选择仍依赖现有原生加载器支持的格式，不承诺完整浏览器 picture 的媒体查询和 sizes 求值。

远端 SVG 内容与余额可以变化，客户端只展示收到的图片；线上渲染结果与离线夹具分别记录。静态 SVG 的动画、滤镜及部分现代 CSS 的渲染差异依旧存在，渐变文字预计算的维护代价与边界见 SVG 解码笔记。
