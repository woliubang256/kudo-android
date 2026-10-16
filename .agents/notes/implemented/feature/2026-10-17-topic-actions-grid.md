# Agent Note: 帖子操作菜单两排展示

Status: implemented

## Problem

帖子菜单增加私信后，举报等按钮被横向裁出可见范围，用户无法一次看全操作。用户要求按钮多时分两排，不依赖横向滚动发现入口。

## Decision

保留圆形图标、文字、权限与点击语义，仅把操作区改为最多四列的换行布局；常规手机多于四项时分两排，窄屏按 80dp 的最小单元宽度减少列数。每项占等分列宽，第二排与第一排对齐，不引入水平滚动。使用稳定的 BoxWithConstraints、Column 和 Row，不升级 Compose 或导航依赖。现有取消按钮和弹层拖动关闭不变。

本篇部分替代[主题操作](../../implemented/feature/2026-10-09-topic-actions.md)的单排横滑布局，网络、账号与私信上下文契约保持不变。检索活跃笔记后，[底部面板外观](../../implemented/simplification/2026-10-11-sheet-chrome.md)仅涉及容器，与本次操作区布局部分重叠；没有完全吸收的旧笔记或过时提案。

## Alternatives considered

- 继续单排横滑能保持原高度和按钮大小，但隐藏了常用操作，且用户明确要求全部展开，因此不采用。
- 新的 Compose Grid API 可以直接描述列宽和间距，但当前项目不需要为小型菜单升级到实验版 Compose；使用现有稳定布局组件。

## Testing

TopicActionsUiTest 先复现操作区仍存在 HorizontalScrollAxisRange（artifacts/action-grid-repro.txt），再验证七项常规宽度菜单分两排、全部项直接可见且私信和举报可点击，以及 280dp 深色 1.3 倍字号六项菜单分两排。与复制、收藏、历史、举报、屏蔽和取消回归共 12 项在 RMX2202 / Android 13 通过（artifacts/action-grid-device.txt，20.809 秒）。

## Consequences

按钮不再藏在横向视口之外，代价是菜单高度随第二排增加；极窄窗口可能需要更多行以保留点击区域，不能通过压缩图标或重新引入横滑掩盖溢出。
