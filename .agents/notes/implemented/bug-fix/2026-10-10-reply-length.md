# Agent Note: 普通回帖遵循网站和分类字数规则

Status: implemented

## Problem

编辑器只判断非空，会把网站不接受的短回帖提交到服务器；网站的分类覆盖规则并非统一16字。

## Decision

回复编辑器从预载siteSettings读取min_post_length和max_post_length，从/site.json读取当前分类custom_fields.warden_min_post_length。正数分类覆盖优先且不超过最大长度。读取失败阻止发送，普通错误提供重试，Cloudflare 验证错误使用[统一过盾入口](2026-10-11-cloudflare-verification-flow.md)并在验证成功后只重新读取规则，草稿始终保留。已知正数分类时只请求两份只读配置，不重复读取主题。

没有正数分类上下文时，规则入口只读 /t/{id}.json 的 archetype 和 category_id；只有服务端明确返回 private_message 才读取私信最小字数与网站最大字数，不查询 /site.json 的普通分类。其他主题仍按实际分类覆盖处理，类型缺失且分类不可用时继续报错，不猜测为私信。这里只需元数据，避免完整 TopicDetail 解析丢弃 archetype 后再误走分类分支。

最小长度沿用网页UTF-16算法：排除单行HTML注释和BBCode引用，折叠网站定义的空白并忽略首尾空白；超过10000码元时与网站一样直接计原始长度。最大长度检查原始文本。UI禁用与ViewModel发送前复核同时存在，发送成功后的清理仍遵循[账号与草稿](../feature/2026-10-07-account-and-composer.md)。

普通回复的底部提示只保留计数：低于服务端最小长度时显示“当前 / 最少 字”，达到后显示“当前 字”；不追加“回复至少/还需”或草稿自动保存文案。配置读取失败、上传/发布过程和实际发送失败继续各自显示状态，发布前校验不变。

## Existing note audit

[账号与草稿](../feature/2026-10-07-account-and-composer.md)部分重叠，互链且保持发送互斥和确认后清理；[酷安式表单](../feature/2026-10-10-coolapk-forms.md)保持键盘与拖动面板约束；[Boost](../feature/2026-10-10-post-boosts-and-length.md)使用独立短回复协议。没有完全吸收或过时记录。

## Alternatives considered

- 固定16字能减少配置请求，但实际分类存在4、6、8、10、12等覆盖值，因此以网站配置为准。
- 仅依赖服务器校验不需要维护本地计数，但会产生可预防的失败并缺少剩余字数提示，因此本地校验并保留服务器最终裁决。
- 直接把 category_id=0 当作私信能省去一次详情请求，但无法区分缺失分类的普通主题或不完整响应；用服务端 archetype 明确识别，避免错误放宽普通回帖限制。

## Consequences

打开回复需读取配置；配置不可用时不能发送，但重试不会丢失输入。网站未来改变计数或覆盖字段时需要同步实现。

## Verification

EditorLifecycleUiTest先复现不足字数仍发送（expected 0 but was 1），修复后5项真机测试通过，覆盖边界、失败保留、配置重试、草稿隔离和确认清理。ReplyRulesTest三项通过，覆盖空白、嵌套引用、UTF-16、分类覆盖和只读请求。CommunityFormsUiTest的键盘/拖动用例适配真实规则后通过，短文本禁用与合法草稿重开均验证。测试不向社区发布内容。

QuickScrollToTopUiTest.replyCountIsCompactAndTheMinimumStillControlsPublishing真机通过（artifacts/quick-top-final-ui.txt），验证0/5字显示最低6字比例、6/10字仅显示当前字数，且发布按钮仍在达标后才可用；完整回顶集成与验证见[快速回顶](../feature/2026-10-11-quick-scroll-to-top.md)。


## Private message follow-up (2026-10-17)

新建私信与私信会话内回复均读取 min_personal_message_post_length 和 max_post_length。前一轮仅接入 PrivateMessageScreen，没有覆盖共用 EditorViewModel 的会话回复；截图中的“暂时无法读取当前分类回帖规则”来自该遗漏。规则层统一分流后，原有底部回复面板无需改动，仍沿用 UTF-16 计数、发送前复核、错误重试和草稿保护。

ReplyRulesTest 的新增私信夹具先复现无分类时误查普通分类的错误，同时暴露传入零分类时未重新读取主题元数据。夹具分别覆盖未提供分类与零分类的私信、未知普通分类、缺失类型以及缺失私信规则，核对私信只发出详情和预载配置两个 GET 请求，不依赖 /site.json 或固定字数兜底。

本轮 ReplyRulesTest 共 8 项通过。RMX2202 / Android13 上新增 PrivateMessageReplyUiTest 的 3 项通过：使用与生产相同的 repository.replyRules 读取夹具，回复面板按私信最小字数显示计数、达标前禁止发布且只在明确点击后触发模拟提交，规则缺失后的重试保留草稿且不发送，新建私信仍只读取预载配置。另有 EditorLifecycleUiTest 和 EditorVerificationUiTest 共 13 项通过，覆盖普通回复、分类草稿与过盾重试；合计 16 项真机用例，没有向真实社区发送内容。

开发版保持已安装的 0.2.10-dev / 2021；核对已安装 APK、构建 APK 和测试 APK 的证书一致后，使用 adb install -r 与 adb install -r -t 覆盖安装，未卸载或清除数据。真实私信页面的账号验收由用户完成，确认前不推送代码或 Tag；先前生成的 0.2.9 草稿已撤下，正式发布时需按最终 App 提交重新生成。

