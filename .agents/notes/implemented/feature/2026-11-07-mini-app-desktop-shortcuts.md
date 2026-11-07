# Agent Note: 小程序桌面快捷方式

Status: implemented

## Problem

用户需要从小程序更多菜单把当前应用添加到桌面，并直接恢复其独立任务。

## Decision

使用 Android 26 起提供的 ShortcutManager.requestPinShortcut，让桌面负责确认与摆放。快捷方式名称、图标取应用信息；ID 使用账号与应用 ID，Intent 复用[独立任务](../../implemented/feature/2026-11-07-mini-app-independent-tasks.md)的经过校验的地址与身份。点击不会切换登录账号，账号不匹配仍由运行页拒绝。快捷方式的来源任务设为未知，回论坛时由任务管理器查找主任务，不能前置自己。

快捷方式创建器由运行页菜单调用。系统不支持固定或请求失败时提示实际结果，提交请求不等同用户已确认添加。

## Existing note audit

检索桌面、shortcut、独立任务：独立任务提供身份，浮窗提供在内存中的恢复；两者互补。其他命中为发布或网络白名单，均无关，无需归档。

## Alternatives considered

- 旧版广播添加图标兼容更旧系统，但项目最低版本为 26，且绕过桌面确认、结果不可知；采用系统固定快捷方式。
- 仅保存公共 URL 能交给任何浏览器，但无法保留账号隔离与独立任务身份；使用显式应用内 Intent。

## Testing

任务身份单测覆盖账号和应用的稳定 ID。RMX2202 / Android 13 真机由用户确认：从浮窗预览恢复正常，添加桌面并从图标进入正常；dumpsys shortcut 确认桌面存在 Pin 标志、Music Player 名称及当前账号/应用组成的稳定 ID。用户未专门终止进程，不把该结果记作强制冷启动验证；其他启动器和不支持固定的桌面仍是兼容性边界。

## Consequences

桌面决定图标角标和是否支持固定；已固定快捷方式不会随退出登录删除，但不能绕过账号校验。
