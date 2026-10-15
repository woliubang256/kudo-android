# Agent Note: 标签跳转保留名称与服务端标识

Status: implemented

## Problem

目录展示“公告”，进入后却请求缺少数字 ID 的 444-tag 路径，标题直接显示路由 slug。目录和搜索解析丢掉服务端标签 ID，各入口又硬编码 id=0。标签名称、slug 和数字 ID 是不同字段，不能互相替代。

## Decision

TopicTag 保留可选数字 ID，目录、正文和搜索解析统一保留；所有原生标签入口传递名称、slug 和 ID，标题立即使用名称。话题接口返回的 tag 元数据可补充外部链接的名称。生成式数字 slug 在缺少显式 ID 时兼容提取 ID；普通文字 slug 保留旧路径。列表筛选和服务端分页链接继续保持标签范围。

## Related decisions

检索 proposed / implemented / rejected。与[目录](../../implemented/feature/2026-10-15-community-directory.md)、[搜索](../../implemented/feature/2026-10-14-search-experience.md)及[主题展示](../../implemented/feature/2026-10-09-topic-presentation.md)部分重叠，仅补足三字段跳转契约，保留各自布局与权限边界，没有完全取代的旧决策。

## Alternatives considered

- 只把标题换成名称：修正最明显的显示且改动小，但错误请求仍然返回不存在，不能解决加载。
- 所有标签都直接用名称请求：现有旧接口可接受部分名称，但丢失服务端 canonical ID 与 slug，不能稳定处理改名和生成式 slug；保留完整身份并优先服务端 ID。

## Testing

FeedRepositoryTest 的 444-tag 缺 ID 夹具在修复前返回 ApiFailure，修复后通过 canonical 路径和返回名称断言。FeedRepositoryTest、CommunityDirectoryTest、SearchDataTest 及链接相关 JVM 测试通过；真机 TagDestinationUiTest 与 DeepLinkUiTest 共 6 项通过，覆盖名称加载和错误态保留、已有链接行为。使用同签名 2011 / 0.2.6-dev 覆盖安装，保留应用数据。

## Consequences

各入口保留可读名称和 canonical ID，错误请求不再依赖目录展示名称猜测。真实权限不足或已删除标签仍可能返回错误，不应为消除错误而伪造空列表。未知外部链接在收到标签元数据前显示通用“标签”，不把数字 slug 当名称。
