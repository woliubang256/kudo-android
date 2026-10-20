# Agent Note: 动态头像与按展示场景选择角标

Status: implemented

## Problem

只读取 avatar_template 无法可靠展示动画：官网缩略图可能已经静态化，增加 GIF 解码器仍拿不到原动图；把整个容器裁成圆形也会截掉右下角资质或状态。本人账户入口和公开个人页即使显示同一用户，角标优先级仍不同。

## Decision

首页账户入口、“我的”、话题列表、楼层和公开个人页复用同一个 Avatar。组件显示官网已有的头像与状态、资质设置，不增加编辑入口；数据、补齐缓存、动图解码和统一展示各自保持独立边界。

公共 Member 保存 animated_avatar、flair_* 和状态。账号、用户资料、用户卡片使用 status；帖子使用 user_status。列表没有状态，只对缺少资质且状态未知的可见作者合并请求 /user-cards.json，使用有上限的短期内存缓存。完整详情中的空状态表示确实没有状态，不能拿旧缓存补回。缓存随账号切换销毁，卡片失败只影响角标，不能使列表报错。自定义表情 URL 来自 /emojis.json，复用既有 HTTPS 图片请求边界。

Avatar 的展示场景显式区分账户入口和公开内容，不能用“是否本人 ID”推断：账户入口只展示有效状态，公开内容优先展示资质，无资质时展示有效状态；自己的公开个人页同样使用公开规则。状态 ends_at 到期后隐藏。图片和角标分开布局，只裁切头像本身。Neo 的用户名按官网规则匹配，圆角为边长 10%，其余用户均为圆形。

用户要求帖子 Boost（回应短条）内的22dp小头像不显示右下角图标。该场景显式使用 PLAIN，只显示头像及原有形状、动画，不解析或绘制资质、状态角标，也不补齐角标元数据；用户名、正文及表情和点击主页保持不变。其余公开头像继续使用 PUBLIC，不能全局移除角标。

话题详情楼主和完整回复同样使用 PUBLIC 保留头像挂件。昵称后仅显示未由头像展示的有效装饰，具体去重和位置验证见[头像挂件与昵称图标分工](../bug-fix/2026-10-11-topic-avatar-name-decorations.md)；不以消除重复为由把头像挂件迁移到昵称后。

图片优先使用 animated_avatar，失败回退 avatar_template。普通头像请求统一为 256px，超过时使用 512px，不按每个 dp 尺寸生成不同 URL；缓存键包含实际资产模板或动图地址和请求尺寸，避免同一头像在列表、楼层、主页重新读取。较大头像及公开主页背景以同一资产的 256px 内存项占位，高清在后台接管；资源变化不复用旧图，动图失败的静态回退不污染动图缓存键。按内容识别格式，不以 .png 后缀判断；APNG 需要额外解码能力。动画只在前台可见时播放，离开组合或退到后台后停止；资质动图复用同一能力。

共享图片加载器额外识别 ISO BMFF ftyp 的 avis 品牌并使用同版本 AVIF 模块。3004973 的第 2 楼实际是 512×512、44 帧的 AVIF，Android 13 静态解码器只返回封面。接管前限制输入 16MiB，再通过 native 元数据检查边长 4096、4M 像素和最多1000帧；AVIF 不支持采样，因此不能沿用 APNG 的采样假设。正文和查看器均绑定 Drawable 生命周期，正文小附件尺寸规则见[图片布局](../bug-fix/2026-10-09-article-image-layout.md)。

AVIFDecoder 固定 getSampleSize=1，却继承会缩小像素缓冲的 setDesiredSize；在64px样本按32px显示时产生 Buffer not large enough for pixels 并输出透明帧。适配层覆写 setDesiredSize 固定1，保证帧缓冲与实际解码尺寸一致，不修改共享 APNG/WebP 实现。

AVIF 模块保持 APNG4Android 3.0.5，直接使用的 native 元数据 API 固定为该模块运行时依赖的 org.aomedia.avif.android:avif:1.1.1.14d8e3c4，避免声明与实际加载版本不一致。libavif 官方 v1.1.1 许可随包保存在 assets/licenses/libavif.txt，关于页展示 BSD 2-Clause 署名与官方许可链接。

动画暂停直接绑定 LifecycleResumeEffect 的 onPauseOrDispose。仅收集生命周期状态后等 Compose 重组不能保证后台停止，因为宿主暂停时重组器本身也会暂停；图片在后台完成加载时还必须立即停止 Drawable。可见性控制保留，用于首页折叠后仍在组合中的头像。全局加载器由 Application 提供，初始化与受限网络配置见[原生架构](../architecture/2026-10-07-native-community-client.md)。

## Evidence and related notes

2026-10-08 官网只读核查：common_theme_138 明确为 /neo/ 头像设置 border-radius:10%；Neo 的 flair_url 是 battery-quarter，颜色 #00aeff / #fff。当前账号入口显示 no_entry_sign 状态，公开资料显示 GIF 资质。热门列表返回 GIF、WebP 的独立 animated_avatar，常规 .png 头像实际可能为 JPEG。/user-cards.json?user_ids=1,218,18538 与 /emojis.json 返回 200，后者含自定义 bili_118 的上传地址。脱敏字段证据在 artifacts/avatar-web-*.json。

历史笔记检索分类：[原生架构](../architecture/2026-10-07-native-community-client.md)、[数据契约](../architecture/2026-10-07-discourse-data-contract.md)、[账号入口](2026-10-07-account-and-composer.md)、[界面密度](2026-10-07-coolapk-density-and-motion.md) 部分重叠，分别保留网络、会话、导航和触控尺寸的决定；[客户端验证](../testing/2026-10-07-client-verification.md) 保留真机测试流程。不归档任何旧篇，未发现需否决的同主题提案。

## Alternatives considered

- 仅在 Boost 传入的 Member 上清空 flair 和 status：不改组件API，但公开场景的元数据补齐可能重新填回角标；使用显式 PLAIN 场景保证隐藏不被异步读取覆盖。
- 把全部22dp头像都隐藏角标：只需判断尺寸，但会误改其他公开展示；按具体场景传递参数。

- 只注册 Coil GIF 解码器：集成少且沿用图片缓存，但不能解决 APNG，也无法从静态缩略图恢复原动画。
- 用 WebView 播放 AVIF：浏览器编解码完整，但每个附件额外维护网页生命周期与触摸层；现有库已有同版本 AVIF 模块，保持原生图片、缩放及来源动画。
- LINE ApngDrawable：接口简单、支持 Android Drawable，但原生层加载全部帧，指定显示尺寸不等于降低原始帧内存；列表中多头像更适合按帧解码与采样的实现。
- 每个 Avatar 请求用户资料：组件独立、更新直接，但滚动会造成请求风暴；采用可见请求合并、批量用户卡片和有上限的短期缓存。
- 用当前用户 ID 决定角标：无需增加展示参数，但会错误地把本人帖子和本人公开个人页也换成状态；使用显式场景。
- 以 admin 字段决定头像形状：能适应新增管理员，但官网样式只指向 Neo，其他管理员仍应圆形；按站点现有规则匹配用户名。
- 只由 Compose 状态驱动动画停止：可见性与生命周期都能汇成一个布尔值，调用简单，但 Activity 暂停时重组器可能已停止工作；直接绑定生命周期清理，状态仍负责滚出屏幕后的暂停。

## Verification

2026-10-20：新增跨列表 35dp、楼层 40dp、主页 60dp 的真机复用用例先复现三次图片读取（`artifacts/avatar-continuity-repro-device.txt`）。统一尺寸后，复用一次读取、512px 请求挂起时保留 256px 实图、资产 URL 变化重新读取三项通过，既有形状、角标、静态回退、动图播放和后台停止等共 9 项通过（`artifacts/avatar-cache-final-device.txt`）。原首页折叠手势用例在下滑后未恢复头像可见性，两轮失败，不把它计为通过或为此修改首页滚动逻辑；后续验证继续单独记录。35 项头像与资料关联 JVM 测试通过（`artifacts/avatar-profile-build.txt`）。

2026-10-10：TopicBoostsUiTest 先复现回应短条内仍有资质角标（artifacts/boost-plain-repro-device.txt），PLAIN 修复后断言资质及状态均隐藏、其他场景保留、正文表情和用户点击继续正常。20项关联JVM、Debug与测试APK构建及Lint通过（boost-plain-final-build.txt）。真机首轮5项中头像形状、场景优先级、Boost输入及新增角标共4项通过（boost-plain-final-device.txt）；旧大字号用例仍假设深链自动打开完整楼中楼面板，与当前只读回复预览不符。用例改为在实际跳转的第3楼验证Boost与输入面板，复验通过（3.226秒，boost-plain-regression-device.txt），没有为旧用例改变产品行为。boost-plain-avatars.png 已人工确认短条头像无右下角图标，外部对照头像仍有资质/状态。

AVIF 先由确定性三帧真机测试复现只返回静态封面（artifacts/article-repro-device.txt）。接入后同一测试发现上游采样缓冲缺陷（avif-device.txt），修复后5项解码测试及1项正文小图测试通过（avif-fixed-device.txt）；首轮21项相册与代码复制同样通过。动画结论来自红绿蓝真实像素，不只是 Animatable 类型。容器识别、畸形头部、不消耗输入和旧相册恢复的相关 JVM 通过（avif-build.txt）。API26/27及非 arm64 设备仍需后续兼容验证。

最终 ArticleImageSizingUiTest 在真实 RichContent 中采样到 AVIF 红绿蓝三种帧色，并验证 Activity 后台停止、前台恢复和组件移除停止；与小附件尺寸及3项投票用例共5项真机测试通过，日志 artifacts/render-final-device.txt。article-avif-inline.png 已人工检查153dp小图布局，截图仅作为尺寸证据。Debug、测试APK及Lint通过（render-final-build.txt），Lint为0 errors / 30既有warnings / 1 hint。检查打包依赖的 arm64-v8a 与 x86_64 native ELF，加载段对齐均为16384字节；这项静态检查不替代其他系统和架构的真机兼容验证。

新增20项JVM测试覆盖帖子用户 ID、两种状态字段、空值与错误类型、资质、独立动图地址、头部检测和角标策略；批量补齐覆盖合并、缓存上限与过期、失败退避、账号销毁和取消。当前全部57项JVM通过。

RMX2202 / Android 13 的9项头像真机测试通过（19.357秒，artifacts/avatar-lifecycle-instrumentation.txt）。自制64px APNG/GIF/WebP样本在真实加载器的32px画布上渲染三种帧色；另强制执行旧版WebP分支。真实 Activity 后台与恢复、组件移除、首页折叠、静态回退、状态过期、场景优先级、方圆形状及角标外延都有断言，Activity 重建后仍能解码。后台未停与加载器初始化顺序崩溃先由 avatar-ui-instrumentation.txt 复现。浅色与深色130%截图 avatar-light-gallery.png / avatar-dark-large-fallback.png 已人工检查。

最终57项JVM、46项真机回归全部通过（101.177秒），Debug与测试APK构建通过，Lint为0 errors / 28 warnings；日志为 avatar-final-build.txt / avatar-final-instrumentation.txt。真实首页、楼层和Neo公开个人页的圆角方形与蓝色资质已检查，普通作者的圆形与图片资质也已检查，材料为 avatar-real-home.png / avatar-real-topic.png / avatar-real-neo-profile.png / avatar-real-list-frame-a.png。实景截图不是多帧播放证明，动画播放结论来自上述确定性帧测试。

真实列表有一位作者头像仅显示底色。官网同一账号的 animated_avatar 为空，返回118字节的288×288静态PNG，所有像素Alpha都是0（avatar-blank-source.json与对应原图）。保留用户的透明图内容，不把它作为加载失败替换为首字母。

分发与手机安装包SHA256均为 c1b8c09ae6411f3aeb9408a5609fe2841afa5657753cabb4511d0a36eb04d5aa，记录为 avatar-apk-verification.txt。

## Consequences

同一用户在所有入口保持相同形状与动画能力，角标由展示场景决定。可见作者共享批量补齐与表情目录，避免逐头像请求；元数据在换号时销毁。

原始动图可能远大于缩略图，补充解码器限制输入与画布，并按目标尺寸采样。自定义表情和资质依赖远程资源，错误时保留头像和可读语义。未知资质图标名使用通用认证图标。用户卡片可能因访客权限或验证不可用，此时仅显示已返回的元数据。通用状态更新以重新进入页面及缓存有效期为准，不增加独立的状态推送连接。

APNG 使用确定性样本验证，未找到可稳定复用的真实站点APNG头像。API26/27尚无真机验收，API33强制执行旧版WebP分支不能替代旧系统兼容性结论。真实手机仍为访客；本人入口的状态/资质优先级使用账号夹具验证，不将浏览器账号视为手机登录。
