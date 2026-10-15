# Agent Note: 网络管理中的并发与滑动窗口调度

Status: implemented

## Problem

已有RequestCooldown在429后阻止同源继续请求，图片另有4个响应体许可证，但API与图片没有共享的主动请求节奏。快速浏览可以在收到首次429前集中发出请求，用户也无法调整。用户要求参考FluxDo的并发与限流设置，已确认预设、自定义、即时生效和恢复默认的方案。

## Decision

按scheme/host/port共享并发名额与滑动窗口，请求开始前等待本地额度；同源按进入顺序放行。并发覆盖响应体读取，EOF、关闭或异常只释放一次。网络拦截器接入API、Connect和图片，每次真正的HTTP交换都计数，缓存命中不占额度；DoH开关不改变调度。MessageBus长轮询、NativeDownloadManager文件下载、GitHub更新和WebView继续走各自通道，不占短请求名额；复用imageHttpClient的原图导出仍受图片调度约束。

默认均衡为并发3、每3秒6次；慢速为2/6/5，极速为6/15/3。自定义范围为并发1～10、阈值2～30、窗口1～10秒。配置独立存储，不触发DoH重连；更新立即唤醒等待者，缩小并发不取消在途请求，扩大窗口仍参考最近10秒的请求历史。排队沿用现有调用总超时，每100ms以内检查取消与服务端冷却。冷却使等待者立即失败，写请求不自动重放。

设置入口与页面标题改为网络管理，保留DoH/ECH、VPN和服务器管理。底部新增高级区，预设按钮用LogoYellow选中态，高级参数可折叠，自定义时展开。恢复默认使用现有液态玻璃弹窗，只重置请求节奏。

滑块仅采样卡片内独立的背景兄弟层，不能引用包含自身镜片的祖先LayerBackdrop；弹窗在内容层之外采样页面。真机自定义参数用例先复现了祖先采样引发的RenderThread递归栈溢出（artifacts/network-management-custom-device.txt、network-management-crash.txt），隔离采样后同一用例通过。设置行同时显示DoH状态与当前并发数，避免DoH关闭时误以为整个调度关闭。

## Existing note audit

检索限流、并发、RequestScheduler与请求调度。[主站冷却与图片退避](../bug-fix/2026-10-14-request-rate-cooldown.md)部分重叠，保留429、图片4路上限和单次安全恢复，在其上增加可配置的同源共享门限；[DoH网络](2026-10-12-doh-network.md)部分重叠，保留路由、凭据边界及写请求不重放。通知流与网络传输修复不改变。无完全吸收或过时提案。

## Reference

参考FluxDo快照e1bd839的request_scheduler_config.dart、rate_limit_card.dart和请求调度行为，仅借鉴交互、参数与调度意图，独立编写Kotlin实现，不移植GPL源码。

## Testing

核心RequestScheduler与网络拦截器已实现，8项离线测试通过（artifacts/request-scheduler-core.txt）：共享并发覆盖正文、窗口精确到期、扩大窗口保留历史、动态调整、等待取消、异源隔离、429打断等待、逐跳计数与读取失败释放。

RequestSettings将version 1格式的request_limits独立存入kudo_network，缺失配置默认均衡，损坏字段分别修复。AppContainer为API、Connect和图片安装同一个调度器，下载及MessageBus仍用原路由；API/Connect保留排队期间的429错误类型，DoH状态不将本地冷却误报成连接失败。配置3项、接入3项及既有API/图片/Connect定向回归通过（artifacts/request-scheduler-integration.txt）。RequestSettings、RequestCooldown及DohRequestPolicy另9项定向回归通过（artifacts/network-management-verified-build.txt）。

RMX2202 / Android 13上的NetworkManagementUiTest 3项、RequestSchedulerDeviceTest 2项和DohSettingsUiTest 3项通过（artifacts/network-management-device-fixed.txt）。覆盖预设保存、自定义参数重进、折叠、重置确认与取消、DoH/VPN及服务器增删改回归；localhost真实RustSocket的HTTP/2请求验证正文并发、共享窗口以及本地冷却不会污染DoH连接状态。使用测试APK独立偏好，未改正式用户配置。开发版保持0.2.5 / 2006，同证书install -r覆盖安装，保留用户数据。

深色预设、自定义与液态玻璃重置弹窗截图为artifacts/network-management-{presets,custom,reset}.png，目视核对黄色选中、参数与面板排版。

浅色130%字体下新增1项触摸用例通过（artifacts/network-management-touch-device.txt），验证预设换行、点击及拖动滑块后的持久化，以及重置按钮可见可用。对应light-large与reset-light-large截图目视检查通过，系统原有auto夜间模式未改。真机共9项通过；最终Debug/测试包构建和Lint通过（artifacts/network-management-final-checks.txt），0 errors / 46 warnings / 1 hint，与任务前基线一致。

## Alternatives considered

- 只增加设置界面接入OkHttp Dispatcher最省改动，但无法在多个客户端间共享窗口，响应回调结束也不代表正文下载完成。
- 在页面仓库方法外包一层协程锁可避免占用网络线程，但容易遗漏图片和重定向；在实际交换处统一约束，保留调用现有超时和取消。
- 将长轮询和下载一起限制最统一，但长连接会长期占满看帖名额；保留它们各自的生命周期和并发控制。
- 429后排队恢复可免手动重试，但会积压过时请求并有重复提交风险；本地平滑排队与服务端强制冷却分别处理。

## Consequences

接口与图片在429之前共用可调整的节奏，配置即时生效且不重建DoH。代价是突发请求需要排队；网络线程等待额度有现有总超时兜底，极慢参数或持续排队可能耗尽调用超时。正文必须按OkHttp契约关闭或读完才能归还名额。图片原有4路保护保留，调高共享并发不能突破该局部上限。

本地设置只能降低请求突发，不能消除账号/IP等服务端限流，极速模式仍受429冷却约束。真机覆盖Android 13，其他系统的材质继续沿用公共组件的版本降级。
