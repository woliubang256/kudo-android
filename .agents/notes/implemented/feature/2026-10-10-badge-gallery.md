# Agent Note: 徽章宫格与获得条件详情

Status: implemented

## Problem

“我的徽章”是仅已获得徽章的长文字列表，缺少图标与未获得状态，点击跳转网页，难以浏览和查看获得条件。

## Decision

专用徽章页面合并/badges.json目录与/user-badges/{username}.json?grouped=true的个人授予记录。获得状态仅以指定用户的授予记录为准，不使用目录中当前会话的has_badge。目录保留long_description，补入目录外的已获得徽章；读取失败不把未知状态显示成未获得。

使用惰性宫格，约100dp最小单元，普通手机三列，窄屏大字减少列数、宽屏增加。宫格只显示圆框图标和下方名字，取消矩形卡片；圆框内部使用主题surface，页面背景使用主题background。已获得先列出，其他按服务端分组顺序排列。圆框与Font Awesome图标使用服务端对应的金银铜色，未获得使用主题中性灰；图片徽章保留原图，未获得转灰。已知站点图标随应用打包，未知图标回退通用徽章。

“已获得 x / y”移至顶栏右侧，移除常驻刷新按钮和宫格顶部统计行。加载或失败时隐藏未知统计，失败页仍保留重试；重新进入页面重新读取数据。

名称预留两行，超出两行显示省略，弹窗标题保留全名。打包34个站点使用的Font Awesome字形，vote-up-filled使用同义thumbs-up；图片徽章加载失败回退对应字形。授权说明随资源与关于页同步。

点击打开现有贴边、可拖动关闭的CommunitySheet，显示徽章、获得状态、授予时间/次数、服务端详细说明与获得条件，并保留查看官网入口。说明缺失时明确显示暂无说明，不推测门槛。普通账号列表与待审核、聊天、邀请行为不变。

应用内消息列表的授予徽章通知（notification_type=12）保留正数badge_id，通过独立回调进入既有account-records/BADGES原生页面。页面在目录与个人授予记录成功加载后按ID打开同一详情面板；加载失败不消费定位意图，重试成功后仍打开。一次页面进入只自动处理一次，关闭面板、重组和配置恢复不重复弹出；重新点击通知进入新页面可再次打开。缺失、无效或目录中不存在的ID仅显示徽章页，不按名称猜测或误开第一项。点击通知仍按原逻辑标读，返回回到消息列表。

定位参数是原生路由的可选查询参数，不修改网页链接分流、WebView重定向或系统通知载荷；普通个人中心入口不携带定位意图。

## Existing note audit

检索徽章、账号菜单和弹窗。[账号与草稿](../../implemented/feature/2026-10-07-account-and-composer.md)的“仅列已获得”呈现被部分取代，个人授予关联与账号隔离继续成立；[个人中心](../../implemented/feature/2026-10-10-member-account-dashboard.md)的入口保持；[酷安式表单](../../implemented/feature/2026-10-10-coolapk-forms.md)继续约束弹窗交互。无完全吸收或过时提案。

## Alternatives considered

- 直接使用目录has_badge只需一次请求，但它反映当前会话，缺少指定用户的授予时间与次数，不能代替个人记录。
- 网页整页展示能复用官网所有功能，但无法满足用户要求的紧凑宫格与原生详情弹窗。
- 每项仅用通用奖章最简单，但丢失网站各徽章Logo；使用已有字体图标来源与官方徽章图片。
- 拦截所有/badges网页链接可以复用现有URL字段，但会把WebView内浏览也改成原生跳转，越过网页继续浏览的边界；仅消息列表按通知类型与badge_id分流。
- 为通知新增专用徽章路由可以减少既有路由的参数修改，但会维护两个相同页面入口；复用account-records的可选参数，保留个人中心原路径。

## Testing

核对官网82项徽章目录、图片字段、图标名称和long_description，并读取公开用户授予记录确认grouped的count、granted_at与badge_id结构。真实社区只读，不申请或修改徽章。

14项相关JVM测试通过，其中BadgeDataTest的4项覆盖未获得、个人授予关联、目录外已获得、重复授予、缺失字段及只读GET请求（artifacts/badges-signature-checks.txt）；AccountExtrasTest的3项继续覆盖待审核与邀请。Debug与测试APK构建成功，Lint为0错误、30项既有警告和1项提示。

RMX2202 / Android13的BadgeGalleryUiTest共4项全过（artifacts/badge-gallery-final-device.txt），验证360dp三列、700dp六列、280dp暗色130%字号两列、图片灰化像素、全部已知图标、获得次数/日期、条件内链接、官网入口及拖动关闭。已查看badges-grid-light.png、badges-grid-wide.png、badges-grid-dark-narrow.png和badge-dark-narrow-details.png。首次链接检查错误地点击整行Text语义；改为点击链接字形的实际位置后通过，产品链接回调未改变。

最终日期标签使用“获得时间”，避免将grouped接口时间推断为最近一次获得；重新构建并覆盖安装后，详情文案、条件链接和拖动关闭的定向真机用例再次通过（artifacts/badge-gallery-time-label-device.txt，1项）。

圆框版Debug和测试APK构建、覆盖安装成功。3项相关真机用例通过：360dp三列、统计在顶栏右侧且无刷新、圆框与图标同心、图片灰化、详情链接及拖动关闭（artifacts/badge-circle-device.txt中的2项）；700dp六列、280dp暗色130%字体两列、41/82统计与标题完整单行、详情可滚动（artifacts/badge-circle-header-final.txt的1项）。已查看badge-circle-light.png、badge-circle-wide.png和badge-circle-dark-narrow.png，金银铜圆框、无矩形卡片和主题背景符合要求。截图使用离线图标/图片夹具。

宽窄切换首次检查将TextLayoutResult保留的排版可用宽度误判为截断（paragraph=624、Text.width=294，但实际字形right=294且截图完整）。改为核对单行、全部字符、无省略和实际字形边界后通过，不放宽可见性或修改产品文字尺寸。

徽章通知定向改动先由AccountNoticesTest复现badge_id丢失，再由离线消息夹具复现点击走帖子/网页及原生页面没有自动详情。13项相关JVM测试（AccountNoticesTest、BadgeDataTest、NotificationProtocolTest）通过；RMX2202 / Android13上15项真机测试（BadgeGalleryUiTest、MemberAccountUiTest、NotificationUiTest）通过。覆盖按ID准确定位、消息标读一次、缺失/无效/未知ID不误开、加载失败与状态恢复后的重试、关闭后重组和状态恢复不重弹、返回消息后再次点击仍打开、个人中心无参数入口及非徽章通知不变。结果见artifacts/badge-notice-green-build.txt和artifacts/badge-notice-green-device.txt；已核对badge-notification-details.png。测试网络全用离线夹具，不修改真实通知；核对签名后以原版本2021 / 0.2.10-dev覆盖安装，保留真机数据。

## Consequences

宫格能同时浏览已获得与未获得徽章，详情打开不再请求网络。首次加载需要目录与个人授予两个只读请求。服务端不公开的获得条件无法补出；未来新增图标可能暂时显示通用奖章，官网仍可查看原始详情。

通知定位复用已加载目录，不额外请求单个徽章；服务端未提供ID或徽章已不可见时只能进入徽章页，不能保证弹出对应详情。本次不改变系统通知的点击目标或网页浏览行为。
