# Agent Note: 搜索顶部随结果滚动折叠

Status: implemented

## Problem

搜索页的返回、搜索框和三类标签固定占据顶部，长结果列表的阅读空间比首页少。用户要求红框内这两行像首页一样上滑滑出、反向滚动立即出现，排序与筛选留在状态栏下。

## Decision

SearchScreen 复用首页的 Material3 enterAlwaysScrollBehavior，只折叠返回搜索行和三类标签组成的整体。状态栏留白、帖子排序筛选行和底部分隔保留；页头仍在结果采样层之外，布局阶段按实际偏移缩减高度，同步结果顶部留白与筛选可用高度，不移动整个列表视口。

新查询、清空、账号或类型切换展开顶部；详情返回保留保存的折叠与阅读位置。快速回顶并行展开页头。输入框取得焦点、打开筛选时先展开，筛选或确认面板覆盖期间禁用结果联动和回顶目标，避免面板自身滚动牵动底层顶部。

## Existing note audit

检索搜索、折叠、吸顶、enterAlwaysScrollBehavior，检查 proposed、implemented 和 rejected。[搜索体验](../../implemented/feature/2026-10-14-search-experience.md)部分取代其“只复用标签、不引入折叠”的决定；数据、服务端筛选、类型缓存和阅读位置保持。[首页标签与吸顶](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)提供 enter-always 和状态栏留白方式，但首页自身标签不折叠。[原生顶部材质](../../implemented/feature/2026-10-16-page-toolbar-material.md)的独立采样保持；[回顶方向](../../implemented/feature/2026-10-29-scroll-top-direction.md)只复用回顶时展开动作，不改变显隐门槛。没有完全吸收项或同主题待实施、被否提案。

## Alternatives considered

- 把搜索与标签放入结果 LazyColumn，排序使用 stickyHeader，结构直接但只有返回列表顶部才恢复搜索框，不能反向轻滑即出现；复用首页 enter-always 行为。
- 整个页头一起收起实现最少，但会隐藏用户要求保留的排序筛选及状态栏安全区；只测量、裁切红框内的两行。
- 用首项索引判断向上/向下无需嵌套滚动，但程序定位、不同结果行高和原有阅读恢复容易造成跳变，也不能精确跟手；使用已有 Material3 滚动状态，不改搜索数据与缓存。

## Consequences

- 连续上滑收起返回搜索行及类型标签，排序吸顶；再上滑位置稳定，反向滚动时未到列表顶部也重新显示。
- 关键词、排序和结果保持，回顶展开；切换结果类型、新查询和清空有可见的操作入口。
- 筛选内滚动不折叠页头，关闭不破坏原结果视口；浅深色、三种材质、窄屏大字号及详情返回保持相关回归通过。

动态顶部留白与首项偏移一起变化，需要检查往返手势及回顶是否抖动。类别/标签和用户页没有排序行，完全折叠后仅剩状态栏安全区，反向滚动和系统返回仍可使用。材质观感以设备截图另验，不由布局断言代替。

## Testing

先在旧布局运行折叠、回顶和筛选三项新增真机用例，均因上滑后搜索框仍显示而失败（`artifacts/search-collapse-repro-device.txt`）。修复后覆盖排序吸顶、反向展开、查询保留、筛选内滚动隔离、快速回顶、保存折叠与阅读位置、新查询展开，以及280dp宽/130%字号的用户页与类型切换。

旧短窗口用例在系统键盘出现后将380dp模拟视口减到不足页头高度，按钮不再可见。使用改动前的 SearchScreen 对照仍失败（`artifacts/search-collapse-baseline-device.txt`），不是本次折叠引入；用例在填写完范围后显式收起键盘，等待实际 IME 底部留白归零及布局恢复，再断言应用按钮可见并验证错误提示，不改变产品键盘或范围校验逻辑。聚焦输入会出现独立选择浮窗，因此窄屏用例保留焦点断言，不对多根节点调用通用截图。

SearchUiTest 共20项与首页折叠1项真机回归分批通过：`artifacts/search-collapse-final-device.txt` 中20项成功，唯一失败为短窗口的键盘收起动画尚未结束；补齐等待后，该项单独复跑通过（`artifacts/search-collapse-short-device.txt`）。覆盖点击/横滑缓存、详情返回、历史清空、排序筛选、浅深色三种材质、窄屏大字、折叠恢复和回顶；未重复运行无关全量测试。

SearchDataTest、SearchQueryTest、SearchViewModelTest 共29项 JVM 定向测试通过（`artifacts/search-collapse-regression-build.txt`）。Debug 与测试 APK 构建通过；Lint 为0 errors / 58 warnings / 1 hint，未调整既有告警。

同机查看折叠吸顶、反向展开、深色大字筛选截图，中文可读；截图使用离线 Compose 夹具，不能代替真实社区网络或系统栏窗口验收。RMX2202 / Android13 使用3002 / 0.3.1-dev同签名覆盖安装，保留已有数据，不卸载应用或清除登录。
