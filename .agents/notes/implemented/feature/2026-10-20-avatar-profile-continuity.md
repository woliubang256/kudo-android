# Agent Note: 列表头像到个人主页的连续展示

Status: implemented

## Problem

列表头像已经显示，帖子与个人主页仍按各自显示像素替换 avatar_template 的 size，产生不同 URL 和解码请求。公开主页还等待完整资料才创建头部，慢网时只见加载行。用户要求点击列表头像时有进出共享动画，并且复用已有头像，而不是重新闪回占位。

## Decision

普通头像统一请求尺寸桶（256px，超过时最多 512px），相同头像资产跨列表、楼层和主页复用同一个 Coil 内存项；高清请求使用相同资产的 256px 缓存作为占位。缓存身份由实际头像模板或动图地址决定，资源版本变化必须重新读取，不能仅以 username 作为图片身份。尺寸桶与占位复用由[头像展示](2026-10-08-avatar-presentation.md)承载，并先作为独立修复提交。

既有 Navigation Compose 外层增加独立头像 SharedTransitionLayout，边界动画使用 360ms FastOutSlowIn。来源键包含回栈项、username 和可保存的头像实例标识，只有被点中的头像与公开主页匹配，同一作者其他实例不跟随。可点击来源节点在点击前就保持注册；主页目标节点持有同一 seed，不能等匹配成功才挂接 sharedElement，否则来源位置已经丢失，动画会跳变。展开头部与收起工具栏以 collapseFraction 的一半为界，只允许一个目标共享头像。

点击携带 Avatar 实际显示、经过元数据补全的 Member；来源必须是当前且 RESUMED 的回栈项，防止连点重复跳转。只有同步的同名 openUser 调用接收 seed，直接链接不继承旧来源。seed 按目标回栈项保留，销毁时清理，子页返回不重播列表动画。返回使用真实恢复位置；来源缺失、进程恢复或身份不匹配时退回普通页面过渡。头像本体保留裁切和角标，动图生命周期规则不变，既有整卡快照动画不改变。

外层身份行通过 AvatarProfileLink 与内部 Avatar 共用来源键和点击回调，头像把实际显示的补全 Member 写回来源；点击名字或头像都从同一个实际头像出发，不共享整行。明确传入自身 onClick 的头像保持独立来源，禁用状态和空用户名不新增导航。普通非列表路由通过 avatarComposable 接入相同场景，不改变该路由原有过渡。

公开主页的三接口仍立即独立读取；完整资料到达前使用已知身份绘制只读头部，未知统计保持未知，不开放关注、私信、关注列表或资料操作。真实资料接管显示和权限，不把摘要当作完整资料。无真实资料且请求失败时显示已有错误页，不保留伪完整资料。

## Existing note audit

- [头像展示](2026-10-08-avatar-presentation.md)部分重叠，保留动图、角标、Neo 形状及账号作用域；就地更新尺寸与复用事实。
- [公开主页](2026-10-10-public-user-profile.md)部分重叠，保留三接口、独立失败和权限；加载摘要不替代真实资料。
- [帖子画面连续性](../bug-fix/2026-10-20-topic-card-content-continuity.md)部分重叠，沿用来源身份、子页不重播与失效回退，不修改既有整卡快照动画。
- [图片来源动画](2026-10-09-image-origin-animation.md)只提供来源失效约束，不改变相册返回优先级。没有同主题未落地提案，不归档仍有独立约束的旧篇。

## Alternatives considered

- 所有头像沿用任意显示像素：最少下载和解码像素，但列表 35dp、楼层和主页 60dp 的 URL 不同，已经缓存的图无法直接连续展示；改为有上限的尺寸桶。
- 仅使用相同 username 作图片缓存键：跨屏复用简单，但头像修改、静态/动图与账号切换可能显示过期资产；缓存以实际资源地址和尺寸为键。
- 主页等待全部资料后再开始动画：数据和权限最确定，但会把网速直接变成点击等待；只使用已知身份绘制只读头部，服务器数据异步接管。
- 把整张帖子卡片缩放成个人主页：可复用帖子快照，但头像点击的语义目标是头像而不是帖子内容；本次只共享头像本体。

## Testing

实际 TopicTopBar 的整行点击首先复现未携带来源（artifacts/avatar-entry-repro-device.txt）。增加统一绑定并接入作者行后，原六项与新增作者进出真实像素用例共七项全部通过（11.293 秒，artifacts/avatar-entry-core-device.txt）。入口扩展分为统一绑定、话题相关、用户列表与聊天通知三个单元；引用头像折叠引用、回复预览头像和参与讨论头像不属于既有个人主页入口，不改变它们的点击语义。

先用真机夹具证明同一头像经过 35/40/60dp 显示会读取三次而非一次（artifacts/avatar-continuity-repro-device.txt）。新增三项缓存测试验证一次读取、512px 请求未完成时显示真实的 256px 缓存像素、资源地址变更重新读取；均通过（artifacts/avatar-cache-final-device.txt）。该轮旧用例 collapsingTheHomeHeaderStopsItsAnimationUntilItIsVisibleAgain 的下滑恢复首页头部断言失败，未修改其滚动逻辑，也未证明它在改动前通过。

共享节点首次按匹配条件挂接时，新增的真实像素中间位置测试复现跳变（artifacts/avatar-profile-first-device.txt）。来源提前注册、目标保留 seed 后，六项头像过渡用例全部通过（8.448 秒，artifacts/avatar-profile-final-focused-device.txt）：进出中间位置与一次图片请求、重复作者只移动点击项及拒绝第二次点击、子页返回不重播和来源缺失回退、冷加载身份及错误和权限边界、直接链接不继承 seed、真实资料接管但不移动或重载头像。

关联 36 项 JVM、Debug、AndroidTest APK 和 Lint 构建通过（artifacts/avatar-profile-final-build.txt）；R8 Release 构建通过（artifacts/avatar-profile-release-build.txt）。扩大真机回归首次 48/50 通过（artifacts/avatar-profile-final-device.txt）：新增资料接管夹具遗漏 canFollow=true，修正夹具后随上述六项通过；旧用例 narrowToolbarRetainsAllThreeNavigationTouchTargets 的 40dp 与预期 48dp 断言仍失败，本次未改变工具栏按钮尺寸，不能将扩大回归宣称为全绿。

RMX2202 / Android 13 真机使用实际“旧友”动态头像只读验证列表→主页→原列表位置，已检查 artifacts/avatar-profile/avatar-cycle.mp4 及 avatar-cycle-sheet.png、avatar-opening.png、avatar-closing.png、real-profile.png：头像在移动中保持实际图片，资料异步接管，中文可读，动图和角标保留。录屏为视觉证据，不替代像素断言，也不构成全设备帧率保证。

构建显式使用线上 0.3.0 / 3001。正式签名 arm64-v8a APK 为 artifacts/avatar-profile/kodo_0.3.0_release_arm64-v8a.apk，证书 SHA-256 为 457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418，APK SHA-256 为 76ed67ef8c1b400d8458742ad5f4169c4a8490472d4610ff217243c69dba37ac。真机保留既有开发签名，安装同份 R8 产物重新签名的 kodo_0.3.0_device_preserve-data.apk；覆盖前核对证书 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f，使用 install -r，不卸载、不清数据，firstInstallTime 仍为 2026-10-17 01:33:30。仅本地打包和提交，不推送或打 Tag。

## Consequences

头像共享不再等待完整资料，列表、楼层和主页能够显示同一缓存图；256px 桶会增加最小列表头像的下载与解码尺寸，但避免每个 dp 尺寸各持一份。512px 或独立卡片背景仍可能另取高清，已有头像保持可见，不能保证所有图片零网络请求。

导航 seed 仅驻内存，进程恢复不恢复共享动画；来源被移除时正常回退。SharedTransitionLayout 与现有卡片快照叠层及生命周期保持分离，所有可点击来源保持注册会增加共享节点数量。其他 OEM、系统动画缩放和大列表长期内存行为仍需后续验证；如果小头像下载或共享节点开销成为实际瓶颈，应重新衡量尺寸桶和注册范围，而非仅凭本机视觉结论扩展保证。
