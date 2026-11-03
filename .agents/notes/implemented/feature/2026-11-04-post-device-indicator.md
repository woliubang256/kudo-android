# Agent Note: 显示服务器公开的发帖设备

Status: implemented

## Problem

站点回复提供手机图标及机型提示，原生 App 忽略设备字段。用户要求同样显示，点击图标再展示机型。

## Decision

解析实际生产接口的 ios_device_name 和 ios_device_type。没有非空字符串机型时不显示图标，不通过作者或本机信息猜测。主帖、回复头部和楼中楼弹窗显示小图标，主帖提示“发布自 <机型>”，回复提示“回复自 <机型>”；浮层用根 EditorToolBackdropHost 的同窗口独立采样源，按当前液态、模糊、半透明设置绘制，浅深色保持文字可读。浮层之外的点击、返回、楼层移出组合或设备字段改变会关闭提示，不改变阅读/发布请求。无宿主的独立组件使用不透明底色降级，不冒充真实采样。

## Existing note audit

[帖子归属](../../implemented/feature/2026-11-04-post-reply-recipient.md)部分重叠，共用头部但不更改回复目标。[提及候选](../../implemented/feature/2026-10-29-editor-mentions.md)提供相同浮层宿主，复用其不含自身的采样边界；本篇不更改候选材质选择。检索设备信息和机型，其他命中均是既有真机验证说明，没有重叠决定。

## Alternatives considered

- 底部面板能复用现成表单，但只有一条机型信息时占用空间过大；采用图标旁的紧凑浮层。
- 系统 Toast 实现最少且自动消失，但位置不跟随图标、外观无法跟随材质；使用原生同窗口提示。

## Testing

PostMetadataLiveProbeTest 用生产会话只读请求核实字段，记录为 artifacts/post-metadata-contract.json，不记录 Cookie 或正文。PostDisplayMetadataTest 覆盖缺失、空白、错误类型及已知机型，与图库、回复关系和跳楼相关的 40 项 JVM 测试全部通过。Debug、AndroidTest 构建及 Lint 通过，仍为 0 errors / 60 warnings / 1 hint（artifacts/post-device-library-build.txt）。

PostDeviceUiTest 的 3 个用例在 RMX2202 / Android13 验证点击、外部触摸、返回、设备变化关闭、无数据隐藏、主帖/回复/楼中楼入口和 320dp / 1.5 倍字号。浅深色的三种材质使用红蓝背景实时切换，断言浮层采样随背景变化，截图六种组合已检查（artifacts/post-device-material-review.png）。初批关闭测试在第二次点击后未等待浮层合成便调用返回，导致 Activity 结束；补上真实可见断言后只重跑该项，通过记录为 artifacts/post-device-dismiss-final.txt，未改运行时代码。其他两项在 artifacts/post-device-library-device.txt 已通过。

## Consequences

设备标记保持紧凑，机型仅在点击后显示，不额外请求或上报本机设备。站点仅公开部分客户端设备，旧帖没有设备标记是正常情况。用户提供的 3117423 在当前会话不可访问，字段通过可访问的 3057096 确认：第 8 楼返回 iPhone 14 Pro Max / phone，第 14、18、19 楼也返回机型；不把其他帖子验证视作原帖权限验收。Android 低版本沿用 API 能力降级，其他 GPU 和设备尚未人工验证。
