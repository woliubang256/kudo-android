# Agent Note: Android本地消息通知投递

Status: implemented

## Problem

消息频道已有结构化事件，但尚无系统通知渠道和可跨冷启动恢复的点击目标。权限拒绝、换号与系统通知被划走不能改变后台阅读事实。

## Decision

LocalNotifications通过NotificationDelivery端口显示社区消息渠道的本地通知。通知使用单色小图标、私密锁屏内容、明确MainActivity组件和immutable PendingIntent。目标存入应用私有偏好并使用随机token引用，发布系统通知前同步落盘；外部伪造主题ID或通知ID不能被当作可信点击目标。按账号清理，最多保留40条记录并移除最早通知，避免平台配额溢出。系统点击消费只取消系统展示，后台确认读或账号切换才删除目标记录；从通知栏划走不标读。

权限未开启时不投递或积压补发，应用内计数由独立ledger保持。POST_NOTIFICATIONS已声明，NotificationPermission提供登录后一次请求入口；设置页的管理与主导航接入由[运行通知集成](../../implemented/feature/2026-10-10-runtime-local-notifications.md)负责。头像/目标相关数据不跨账号使用。

完整通知ID可取消论坛或聊天提醒；全部论坛已读只清除论坛提醒，聊天按频道和已确认可见消息位置清理。NotificationLedger保留后台总数、实时优先、首批基线、512条去重及短时同楼层提醒合并规则；分页与实时已读按请求版本合并，旧回包不能覆盖后续标读。

## Related decisions

[统一自适应通知图标](../../implemented/bug-fix/2026-10-15-unified-adaptive-notification-icons.md)部分取代本篇最初的统一单色图标选择：所有厂商均使用彩色自适应资源，最终着色由系统决定；本篇权限、身份存储、投递及已读契约继续保留。

检索运行通知、本地通知与通知频道。[通知协议](../architecture/2026-10-10-notification-stream.md)部分重叠，保持无Cookie跨域传输和游标；[运行通知方案](../../implemented/feature/2026-10-10-runtime-local-notifications.md)部分重叠，本篇负责平台投递和身份存储。没有完全吸收或过时提案。

## Alternatives considered

- 把消息目标直接写入可导出的Activity extras最简单，但外部调用者能伪造标读对象；Intent只携带随机token，内容从私有存储恢复。
- 使用apply异步落盘可减少主线程磁盘等待，但系统通知发布后进程立刻死亡可能丢失目标；投递前commit保证冷启动落点存在，少量短JSON有同步写入成本。
- 所有已读清空全部系统通知维护最少，但论坛已读不能代表聊天频道已读；按明确ID或频道分开取消。

## Testing

LocalNotificationsTest使用独立偏好和离线消息验证真实系统投递、immutable Intent、冷启动目标恢复、消费不标读、伪造token无效、权限拒绝及换号清理。具体通知已读不能取消聊天提醒的问题先在artifacts/notification-chat-cancel-repro.txt复现，再修复，最终两项通过，日志artifacts/runtime-notification-final-device.txt。权限拒绝夹具每次先清理自有测试偏好，避免上轮失败留下的记录干扰断言。平台通知权限通过真实请求面板授予，实际社区写操作未用于测试。

## Consequences

应用内未读与系统提醒分离，OS划走不等于后台已读。未上架也能在运行期间投递；进程结束和Android后台限制仍会停止或延迟接收。系统桌面角标颜色由启动器控制，应用内品牌色角标由集成层实现。偏好记录数量有上限，旧消息始终保留在后台消息列表。
