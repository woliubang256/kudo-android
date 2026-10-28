# Agent Note: 首页横向分页切换

Status: implemented

## Problem

首页 Tab 只能点击切换，共用一份列表状态；左右滑动不能翻页，切回标签会重新加载并回顶。

## Decision

首页使用 HorizontalPager，标题点击和手势驱动同一页码，文字及黄色渐变同步。类别作为最后一页内嵌分类列表，底部分类仍保留独立入口。每个筛选缓存数据、分页及滚动位置，首次访问才读取；只给当前页传入刷新和加载更多动作。账号或 unified_new 改变时重建页码并清空缓存，私有筛选仍在状态层校验。

保留搜索行折叠、下拉刷新和更新提示。更新订阅仅探测当前筛选，切页重建基线；从详情返回不回顶，显式底栏刷新仍回顶。相邻页面不因预组合而额外请求个人列表。

其中固定顺序、类别末页和有限内置集合由[按身份编辑首页 Tab](2026-10-29-editable-home-tabs.md)部分取代。首页可混合系统筛选、目录、一级分类和标签，按稳定来源 key 复用本篇缓存与分页规则；类别目录的组合和回顶目标同时受父页 active 约束。

## Related decisions

检索 Pager、首页筛选、滚动位置、左右滑动。[会话筛选](../../implemented/feature/2026-10-08-session-feed-filters.md)的类别动作和切筛选清空部分被本篇替代，其权限与账号边界保留；[顶部折叠](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)和[更新提示](../../implemented/feature/2026-10-08-home-live-refresh.md)部分重叠，保留布局与推送不扰动阅读的决定。无完全吸收或过时提案。

## Alternatives considered

- 只识别横滑后替换当前列表：改动少，但没有跟手翻页，也无法保留多页阅读位置。
- 每个标签创建独立订阅 ViewModel：状态天然独立，但会增加后台订阅和筛选请求；同一 ViewModel 管理按筛选缓存，当前页负责更新检查。

## Testing

FeedPageCacheTest 验证翻页缓存、切回不请求、分页游标保留以及账号切换清空，随129项JVM通过。HomePagerUiTest 覆盖滑动、标题点击、类别、保存位置和退出隔离；首轮发现生成文件的中文编码错误，修复前的失败在 artifacts/community-features-first-device.txt。

RMX2202 / Android 13 上50项真机用例全部通过（89.212秒），日志 artifacts/community-features-final-device.txt；构建和 Lint 通过，Lint 为0错误、30警告、1提示。已查看 home-pager-hot、nested-reply-preview/thread/dark-large-text 截图。实际联网首页手势切到热门并切回最新，选择语义与黄色渐变一致，记录 community-home-live/hot-live.png 和 community-home-hot/return-layout.json；未操作真实收藏、举报或回复写入。

分发包 artifacts/kudo-community-features.apk 的 SHA256 为 c8f9a72eb701a71c99f54e617b50ef6b75c95020d2fbbbca77f72367cbb6d101。沿用现有 debug 安装签名，不清理账号或偏好数据。

## Consequences

每个访问过的筛选保留列表会增加内存，缓存受有限的 FeedFilter 集合约束。横向图片预览仍由点击打开，列表的纵向滚动和刷新需与横向拖动共同验收。
