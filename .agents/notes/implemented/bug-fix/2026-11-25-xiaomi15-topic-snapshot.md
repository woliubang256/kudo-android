# Agent Note: 小米15进帖截图单变量实验

Status: implemented

## Problem

小米15 / Android 16 / OS3.0.301.0.WOCCNXM 上，正式版0.4.0在进帖附近发生 RenderThread 原生崩溃。2026-11-25 的正式版记录为空 RenderThread 指针，调用方为 CanvasContext::prepareTree 的延迟回调。

## Decision

仅在 Xiaomi / dada / SDK 36 / OS3.0.301.0.WOCCNXM 上，进帖跳过卡片图层分配和离屏截图。复用 fromCard 的串行点击与生命周期检查，以空快照执行原有导航。以 v0.4.0 为基线，不合入半透明默认值改动。

TopicTransitionHost 默认使用设备策略，测试可显式启用原有截图路径。设备策略只匹配已取得崩溃证据的系统构建，系统升级后恢复原路径并重新验证。

## Existing note audit

[整卡画面连续性](2026-10-20-topic-card-content-continuity.md)记录截图动画与无快照回退；本实验部分重叠，仅增加设备边界。[帖子展开过渡](../../implemented/feature/2026-10-19-topic-container-transition.md)的导航身份和返回位置约束继续有效。此前半透明实验在基线标签之后，不纳入此次对照。

## Alternatives considered

半透明材质能减少玻璃效果，但卡片截图仍执行，已有记录中仍然崩溃，无法检验当前假设。

同时直接修改截图和列表模糊能减少更多渲染路径，但无法区分有效因素，本轮只更改是否生成快照的决策。

## Testing

设备策略的6项 JVM 测试先出现2项预期断言失败，加入精确匹配后6项全部通过，覆盖本机、厂商大小写、其他厂商、其他设备、其他 Android 版本与其他系统构建。

新增真机夹具连续进帖返回5次，断言目标帖子与空快照；原有 TopicTransitionUiTest 显式启用截图以保留正常路径回归。首次安装返回 INSTALL_FAILED_USER_RESTRICTED，真机断言尚未运行，不能记为通过。

Debug、AndroidTest 和 R8 Release 构建通过。Release 包核对为 com.kudo.android.snapshot / 0.4.0-snapshot-test / 4001 / arm64-v8a，未启用 debuggable，使用本地测试证书。包内 libkudo_doh.so 的 SHA256 与原正式 APK 一致。

## Consequences

受影响设备使用既有普通进帖动画。无快照也使依赖来源快照的展开动画与列表虚化不触发；即使稳定性改善，也不能据此独立区分截图和关联动效的影响。

原版50轮未复现，稳定复现条件仍缺失。该改动是实验性规避，不证明系统或应用的最终归责。

正式版签名私钥不在本机。用户同意独立包名 com.kudo.android.snapshot；本地构建脚本设置标签“酷DO 截图验证版”和测试签名，生产构建配置不变。验证包复用正式 APK 中的 Rust DoH 库。独立包数据与正式版不同，不能视作严格等价的长期对照。
