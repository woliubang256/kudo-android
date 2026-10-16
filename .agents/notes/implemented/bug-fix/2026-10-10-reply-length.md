# Agent Note: 普通回帖遵循网站和分类字数规则

Status: implemented

## Problem

编辑器只判断非空，会把网站不接受的短回帖提交到服务器；网站的分类覆盖规则并非统一16字。

## Decision

回复编辑器从预载siteSettings读取min_post_length和max_post_length，从/site.json读取当前分类custom_fields.warden_min_post_length。正数分类覆盖优先且不超过最大长度。读取失败阻止发送，普通错误提供重试，Cloudflare 验证错误使用[统一过盾入口](2026-10-11-cloudflare-verification-flow.md)并在验证成功后只重新读取规则，草稿始终保留。已知分类时只请求两份只读配置，不重复读取主题。

最小长度沿用网页UTF-16算法：排除单行HTML注释和BBCode引用，折叠网站定义的空白并忽略首尾空白；超过10000码元时与网站一样直接计原始长度。最大长度检查原始文本。UI禁用与ViewModel发送前复核同时存在，发送成功后的清理仍遵循[账号与草稿](../feature/2026-10-07-account-and-composer.md)。

普通回复的底部提示只保留计数：低于服务端最小长度时显示“当前 / 最少 字”，达到后显示“当前 字”；不追加“回复至少/还需”或草稿自动保存文案。配置读取失败、上传/发布过程和实际发送失败继续各自显示状态，发布前校验不变。

## Existing note audit

[账号与草稿](../feature/2026-10-07-account-and-composer.md)部分重叠，互链且保持发送互斥和确认后清理；[酷安式表单](../feature/2026-10-10-coolapk-forms.md)保持键盘与拖动面板约束；[Boost](../feature/2026-10-10-post-boosts-and-length.md)使用独立短回复协议。没有完全吸收或过时记录。

## Alternatives considered

- 固定16字能减少配置请求，但实际分类存在4、6、8、10、12等覆盖值，因此以网站配置为准。
- 仅依赖服务器校验不需要维护本地计数，但会产生可预防的失败并缺少剩余字数提示，因此本地校验并保留服务器最终裁决。

## Consequences

打开回复需读取配置；配置不可用时不能发送，但重试不会丢失输入。网站未来改变计数或覆盖字段时需要同步实现。

## Verification

EditorLifecycleUiTest先复现不足字数仍发送（expected 0 but was 1），修复后5项真机测试通过，覆盖边界、失败保留、配置重试、草稿隔离和确认清理。ReplyRulesTest三项通过，覆盖空白、嵌套引用、UTF-16、分类覆盖和只读请求。CommunityFormsUiTest的键盘/拖动用例适配真实规则后通过，短文本禁用与合法草稿重开均验证。测试不向社区发布内容。

QuickScrollToTopUiTest.replyCountIsCompactAndTheMinimumStillControlsPublishing真机通过（artifacts/quick-top-final-ui.txt），验证0/5字显示最低6字比例、6/10字仅显示当前字数，且发布按钮仍在达标后才可用；完整回顶集成与验证见[快速回顶](../feature/2026-10-11-quick-scroll-to-top.md)。
