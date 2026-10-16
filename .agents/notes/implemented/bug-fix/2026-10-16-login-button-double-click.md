# Agent Note: 修复登录按钮重复触发

Status: implemented

## Problem

登录页接入玻璃顶部操作后，TopBarTextButton 的外层 Box 和内部 LiquidButton 同时注册点击事件。一次点击会触发两次登录检查，导致会话状态和导航回调发生竞态。

## Decision

玻璃模式下由 LiquidButton 独占点击处理，外层容器只负责尺寸和布局；新增 UI 回归测试验证一次点击只执行一次会话检查。

## Alternatives considered

- 在登录页增加点击节流只能掩盖组件层重复事件源。
- 在 checkSession 内忽略重复调用会让其他合法调用失效。

## Consequences

�����������ְ�ť��������������ظ���Ӧ������ǲ������˰�ť��Ϊ���ֲ��䡣
