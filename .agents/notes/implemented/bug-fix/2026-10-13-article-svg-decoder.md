# Agent Note: 正文和相册通过统一加载器支持静态 SVG

Status: implemented

## Problem

话题3015506的第一张图是1200×420的SVG，CDN返回200和image/svg+xml，文件含嵌套svg、路径、裁切和文字。正文正确提取地址，但全局Coil加载器只注册位图与动画解码器，导致下载后解码失败，重试无效。

## Decision

全局加载器使用Coil 3.3.0的coil-svg，并在KudoApplication的同一个components块注册SvgDecoder.Factory。正文、相册和其他原生图片继续消费同一个加载器，按文件内容识别格式，保留网络Cookie边界、缓存、动图及生命周期行为。AndroidSVG 1.4的Apache-2.0授权文本随APK收录，开源致谢提供对应版本链接。

SvgDecoder 使用 CommunitySvgParser 包装默认解析器。含 tspan／tref／textPath 且存在引用画笔的 SVG 在生成图片前先计算文字边界：SvgTextBoundsCompat 暂时把元素、样式表与请求 CSS 中的引用画笔改为纯色，在目标尺寸记录一次不展示的 Picture，随后在 finally 中恢复全部画笔。实际输出仍由原始 SvgImage 绘制，保留原渐变、尺寸和颜色，不增加网络请求。这样避开 AndroidSVG 1.4 在文字宽度测量期间过早读取空 boundingBox 的异常；无相关文字或无引用画笔的文档不执行预渲染。

对文本根节点省略位置、全部内容位于单个直接 tspan、且子节点使用独立像素 x/y 的常见结构，预计算的边界还补上 tspan 的平移量。AndroidSVG 1.4 原测量器忽略这两个坐标，仅预渲染会把真实文字放在渐变范围之外；此补偿不改变源坐标。多段嵌套、非像素坐标、dx/dy 等复杂文字排版仍交给原解码器，不宣称完整浏览器排版。

兼容类与 AndroidSVG 置于同一 Java 包，通过包可见模型暂时修改画笔，不使用反射和混淆后的类名。此处依赖固定的 AndroidSVG 1.4 模型，升级库时必须复核并优先删除已经由上游修复的兼容代码。

## Existing note audit

- [客户端加载器边界](../../implemented/architecture/2026-10-07-native-community-client.md)：部分重叠，沿用Application工厂和单次components配置，不替换受限网络和动画组件。
- [SVG签名](../../implemented/bug-fix/2026-10-10-svg-user-signatures.md)：部分重叠，签名的CSS动画仍由受限WebView图片容器保留；本次只增加原生静态SVG能力，不取代动画签名决定。
- [相册](../../implemented/feature/2026-10-09-image-viewer-motion.md)：部分重叠，复用已有缩放、采样、返回位置和来源动画，不改相册手势。其余图片布局、头像、导出记录仅背景相关，无同主题旧提案需要归档。
- [picture 来源](2026-11-07-picture-source-images.md)：部分重叠，真实红包 SVG 暴露渐变文字边界异常；该篇持有 HTML 来源选择决定，本篇持有统一解码兼容，不互相替代。

## Alternatives considered

- 为所有SVG建立WebView图片容器可保留浏览器动画，但正文与相册会增加网页实例、触摸和缩放协调成本；本帖是静态图，原生解码器可复用现有图库与缓存。
- 对单个SVG地址改用外部网页能立即看到原图，但不能修复其他SVG附件，也不符合正文和大图一致显示的目标。
- 升级 AndroidSVG 能把兼容维护交回上游，但 Maven Central 的 androidsvg-aar 最新正式版仍为 1.4，主分支的文字测量顺序也尚未消除此问题；不为单处兼容引入未发布依赖或整库源码。
- 用渲染 CSS 暂时覆盖文字颜色可只调用公开 API，但 AndroidSVG 1.4 忽略 !important，且内联 style 的优先级高于渲染 CSS，无法可靠预计算；直接暂存并恢复解析后的画笔覆盖这两种样式来源，最终输出不改色。

## Verification

RMX2202 / Android13使用生产加载器先复现两项解码失败（artifacts/svg-device-repro.txt）：离线嵌套SVG与本帖CDN图片均由平台ImageDecoder报unimplemented。添加解码器后，离线SVG实际渲染红蓝像素，真实CDN图的嵌套标志、裁切与AAI / ECI / VS文字完整显示。

4项SVG真机用例通过：生产加载器的嵌套图形像素、真实CDN图片、无SVG后缀的正文缩略图与独立原图请求及2倍缩放关闭返回、深色主题的真实帖图正文与相册。最终结果见artifacts/svg-fixed-device-final.txt（6项，其中另2项是开源致谢的常规与窄屏大字滚动回归）。关联的3项正文加载/重试/动画生命周期和5项APNG / GIF / WebP / AVIF及Activity重建用例已通过（artifacts/svg-fixed-device.txt）；这些未变动用例没有再次执行，共14项相关真机用例通过。

测试夹具初次复制previous.components时也复制了绑定旧加载器的终端EngineInterceptor，导致后加的离线拦截器不执行。夹具改为保留生产fetcherFactories和decoderFactories，并在新引擎前注册拦截器；未为夹具错误修改产品代码或放宽图形断言。

人工查看artifacts/svg-topic-3015506-decoded.png、svg-topic-3015506-inline.png、svg-topic-3015506-gallery.png，以及svg-inline-light.png和svg-gallery-light.png。实际CDN测试只加载图片，不打开或提交帖子阅读状态。App和测试APK显式构建为0.2.2-dev / 2005；设备与新包证书SHA256均为95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f，adb install -r覆盖安装且不清数据，测试后重新打开MainActivity并保留App。

Debug构建及AndroidTest编译通过，构建记录为artifacts/svg-fixed-build-v2.txt、svg-fixed-build-final.txt与svg-ui-fixture-build-v2.txt。Lint无错误；未执行无关联的全量测试。

渐变文字兼容先在真机复现真实话题与最小离线 SVG 的空指针。首版预计算虽解除空指针，但原始单 tspan 夹具的红色渐变像素仍缺失；补齐坐标平移后，原断言及内联／请求 CSS 的红蓝像素断言全部通过。最终与 picture、普通图片、动图、小程序卡片和既有 SVG 正文／相册合计 20 项真机用例通过，记录为 artifacts/picture-svg-final-device.txt；真实话题的生产加载结果为 imageLoadError=null。此轮使用 0.3.5-dev / 3006 覆盖安装，签名与已装包一致，未清数据。截图与完整复现证据见 picture 来源笔记。

## Consequences

正文和大图可显示静态SVG，图片重试不再重复下载后必然解码失败。原生SVG解码器不保证CSS或SMIL动画、脚本、foreignObject等浏览器特性；现有动态SVG签名保留浏览器图片模式。新增模块增加包体积，解码尺寸继续受现有图片请求约束。签名浏览器容器比原生图片占用更多资源，生命周期规则仍由签名笔记管理；本次没有做内存、帧率或耗电基准，不能量化开启签名的性能成本。

文字边界预计算使相关 SVG 多一次记录绘制，增加这类图片的解码 CPU 成本；不增加位图缓冲区或请求，不改变字体尺寸和布局样式。固定版本的包内模型耦合是明确的维护代价；原生渲染对 CSS 动画和滤镜的能力边界不变。
