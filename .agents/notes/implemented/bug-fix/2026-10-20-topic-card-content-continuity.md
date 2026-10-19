# Agent Note: 帖子整卡展开的画面连续性

Status: implemented

## Problem

共享边界已经从列表卡片展开，但内部保持原尺寸并淡出，冷加载详情替换成加载行。用户提供小红书录屏后指出打开和返回仍像列表动了一下；仅断言实色边界不能证明卡片内容在缩放。

## Decision

保留 Navigation Compose 2、独立来源键和真实回栈，改用点击时的整卡图层快照连接画面。详情保持全屏测量，绘制时连续插值来源矩形、全屏矩形和圆角，卡片快照按宽度等比放大，正文按全屏比例缩放并交叉衔接。加载未完成时保留放大的卡片，显示轻量等待提示，不在动画中途切换空加载页；请求成功或失败后再衔接可操作详情。

列表在匹配导航的 480ms 动画期间留在底层，短暂虚化 6dp 但不移动布局；其他导航不追加虚化或时长。返回从当前正文反向缩小并衔接原卡片画面，读取恢复后的来源位置。来源已删除时原地淡出，不收回过时坐标。点击捕获串行化，期间及导航未恢复 RESUMED 时拒绝重复点击。捕获超过 400 万像素、图层不可用或运行时截图异常时直接使用普通导航。

冷加载保留卡片内容、底部等待提示和可点击的返回按钮；请求结束（含失败）后用 180ms 衔接正文或错误页。整卡内容等比缩放，不拉伸文字；展开到沉浸窗口时逐步避开状态栏，非沉浸窗口不重复加 inset。返回来源的注册可能晚于详情图层首次绘制，因此正文透明度必须无条件观察进度，不能在临时无来源分支停止订阅，导致恢复来源后仍用全不透明正文盖住卡片。

[进场与正文错峰](2026-10-20-topic-opening-render-budget.md)部分替代首次加载时序：请求立即启动，真实正文只在进场完成后组合；携带 Topic 的来源在慢网时先衔接轻量摘要首屏，数据完成后再接管，无元数据来源继续使用本篇的快照等待回退。返回的缩放、来源身份与快照生命周期保持不变。

快照只驻留于对应详情回栈项，ON_DESTROY 或 Host 销毁时释放引用；不写 SavedStateHandle，不序列化位图。进程恢复缺少位图时移除残留来源元数据，使用普通导航。关闭动画沿用 Compose 的系统时长缩放，不增加固定等待。图片相册和其他覆盖层继续使用原有返回顺序。

整卡动画只覆盖首次进入和真实返回来源回栈项；进入用户主页、网页或其他详情后再回来仍是普通二级导航，不能再次从旧列表坐标展开。快照记录首次进入完成，当前组合在离开去子页时关掉首次展开资格；真实回到来源的判定读取当前导航项，不能把所有 PostExit 都当作返回列表。正文接管后的静止帧直接绘制内容，不继续叠画卡片快照。

## Existing note audit

- [共享容器转场](../../implemented/feature/2026-10-19-topic-container-transition.md)部分取代：保留来源身份、回栈、headerHeight 保存和签名安全约束；替代 sharedBounds 的固定内容测量及淡变。其反对快照的生命周期与材质复杂度理由仍成立，但用户明确要求画面连续性，不能继续以边界测试替代内容验收。
- [图片来源动画](../../implemented/feature/2026-10-09-image-origin-animation.md)与[图片相册](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)部分重叠：借鉴矩形插值与失效回退，不改变相册索引、手势或返回优先级。
- [酷安密度与过渡](../../implemented/feature/2026-10-07-coolapk-density-and-motion.md)、[首页标签与吸顶](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)、[公开主页吸顶材质](../../implemented/feature/2026-10-16-public-profile-pinned-material.md)无新决定被取代，继续保留既有导航、列表及玻璃材质边界。其他命中是正文、网络和数据快照，不属于帖子转场。没有同主题未落地提案。

## Alternatives considered

- 继续微调 sharedBounds 的淡变和时长：可以复用导航生命周期，不新增图像缓存；但来源画面依然固定尺寸，冷加载会直接换页，无法满足这次参考中的内容连续放大。
- 只共享封面：图像尺寸变化最自然且内存较低；无图帖子、收藏行和搜索摘要同样要求整卡，不能只修有图入口。
- 保存整屏列表截图作背景：背景留存稳定且方便虚化；每层详情额外占用一张全屏位图，实际导航已能保留进出页到动画结束，因此仅缓存被点击的卡片。

## Testing

TopicTransitionUiTest 先增加带绿色矩形图案的窄卡片；修改前打开时图案不再可见，返回时没有放大的图案，两个用例均失败，日志 artifacts/topic-card-content-repro-device.txt。共享边界匹配不是这次缺陷的成因。补充沉浸窗口用例在 UI 线程设置 edge-to-edge 后复现卡片内容进入状态栏，日志 topic-card-statusbar-repro-device.txt；来源删除用例先复现收回失效位置，日志 topic-card-content-edge-repro-device.txt。来源恢复的透明度订阅回归由 topic-card-key-repro-device.txt 记录。

追加“帖子→子页→帖子→列表”导航回归，先复现子页返回时旧卡片图案重新出现，日志 artifacts/topic-card-child-repro-device.txt；只查纯绿色像素会漏掉父导航的叠加淡变，所以同时断言底部边界及混合后的色差，不能把没有纯色图案当作没有重放。夹具子页明确在 160ms 退场，避免 NavHost 默认的较长淡变覆盖目标页，使边界断言误判；修复前后使用同一夹具验证。

RMX2202 / Android 13 最终 11 项测试全部通过，耗时 15.749 秒，日志 artifacts/topic-card-content-final-regression-device.txt。覆盖图案随卡片放大、冷加载保留画面及成功衔接、返回恢复图案并落回原位、邻项位置与颜色不变、首页 header 首次放置、冷加载返回和快照释放、无来源加载页、状态栏避让、来源删除回退、快速重复点击以及子页返回后再收回列表。测试 APK 显式使用 0.3.0 / 3001、与已装包相同证书覆盖安装，通过 am instrument 执行；不使用自动卸载生命周期的 connectedDebugAndroidTest。

线上最新正式 Release 核对为 v0.3.0，本次保持 0.3.0 / 3001，只构建本地产物，不打 Tag 或发布。Release / R8 / Lint Vital 构建通过，日志 artifacts/topic-card-content-final-release-build.txt；正式包 artifacts/topic-transition/kodo_0.3.0_release_arm64-v8a.apk 为 9,517,418 字节，SHA256 为 90889b321c14d63feca5799b48cb4ed8b83c90fd68917b66dc7706210cf79223，正式证书 SHA256 为 457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418。

保数据真机包 artifacts/topic-transition/kodo_0.3.0_device_preserve-data.apk 从同一正式产物只重签生成，运行时 ZIP 条目逐项相同、不可调试；SHA256 为 ed0be11de33e35103efbb2b0b5a954888bce3df29fe2f60736c13f3615eb9568。先核对手机开发证书 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f 再 adb install -r 覆盖，firstInstallTime 保持 2026-10-17 01:33:30，不卸载、清数据或将正式证书直接覆盖开发证书。

最终同代码优化运行时录屏为 artifacts/topic-card-content-final-release.mp4，带图来源为“[开源] AI 穿越互动小说手机APP及桌面版”，无图来源为“分手了，纪念一下2026年10月19日”；两者均打开后系统返回。逐帧拼图 topic-card-content-final-opening.png / topic-card-content-final-closing.png 及总览 topic-card-content-final-overview.png 分别核对：卡片画面保留、来源矩形向全屏移动、冷加载接管、反向收回和原滚动位置。来源卡片和正文中文实际可读；参考视频中也有图像交叉衔接，不据此推断小红书内部采用某个共享元素 API。这是同机视觉核对，不是稳定帧率或所有入口的性能验收。

## Consequences

转场不再只有边界变化，图像和文字跟着整卡等比缩放，冷请求也保留可识别内容。位图捕获有一次性耗时和内存代价，单卡限制约 16MB ARGB 像素，仍可能额外占用 GPU 内存；多层详情驻留的总开销随回栈增长，没有做长时间内存压力验收。紧凑标题行原本没有正文，冷加载展开后的下方仍是背景和等待提示，不伪造未取得的正文。卡片与正文排版不同，明确允许交叉衔接，不声称与参考使用同一内部实现。虚化在不支持系统 RenderEffect 的旧 Android 上降级；没有多设备帧率或窗口变化的验收结论。
