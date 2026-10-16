# Agent Note: 独立网页验证与挑战误报隔离

Status: implemented

## Problem

“完成网页验证”复用登录回调并打开/login，已登录用户可能跳到个人主页，未进入站点专用/challenge。普通HTML403、无法解析的响应以及页面内Turnstile字样也可能被归为验证。独立推送域名失败会替换首页错误状态，通知流也可能重复提示，造成主站已通过后很快又要求验证。

回复编辑器读取字数规则时把异常转换为字符串，丢失 VERIFICATION 类型，遇盾后仍显示“重试”并重复原生请求；失败后底部还继续显示正在读取，无法准确表达等待用户过盾的状态。

## Decision

挑战识别只接受cf-mitigated: challenge或真实挑战页的结构组合，不把普通登录页Turnstile、文章引用和非JSON内容等同过盾。明确挑战只对GET延迟600ms重试一次，重试读取更新后的Cookie；写请求不重放。非挑战的格式错误归SERVER，普通403保持FORBIDDEN，404保持不可访问。

后台推送验证失败停止当前订阅，不覆盖正常列表或反复提示主站验证，现有重新激活/会话检查可以恢复订阅。主站实际读取失败仍保留验证入口。

App内全屏验证页直接打开https://linux.do/challenge，与原生请求共享Cookie和明确相同User-Agent。右上角依次“刷新”“完成验证”，下方提示“如果页面显示不存在，请多刷新几次，直到出现验证页面；完成过盾后，再点击「完成验证」。”按用户最终确认，页面显示不存在不能视为无需验证或验证成功。刷新使用LOAD_NO_CACHE并始终重新访问/challenge。网页不存在的内容原样展示，工具栏始终可操作。完成按钮先flush Cookie，再检查原生/latest.json的真实话题列表结构；成功关闭验证页并调用原页面只读重试，游客同样可用。网页跳转、onPageFinished、Cookie存在均不单独判定成功；失败留在页面显示原因，不清会话。

错误入口使用统一CompositionLocal承载验证动作，避免逐个页面混用登录回调；验证页作为全屏窗口覆盖在当前导航上，保留原生页面及重试回调，不重放发帖等写入。登录入口及账号检查继续单独负责认证。Connect等级页属于独立域名，其错误入口将LocalWebVerification置空，保留原有打开Connect网页的认证/过盾动作，避免主站验证检查误判独立服务恢复。

回复编辑器的字数规则读取保留原始异常，按 ApiFailure.kind 分流：VERIFICATION 显示“完成网页验证”并通过同一个 LocalWebVerification 打开验证页；普通读取失败仍显示“重试”。入口只传入 reloadReplyRules，成功验证后重新读取规则一次，不自动发布回复；取消或原生验证检查失败不读取规则，草稿和回复面板保留。失败时底部显示等待网页验证或读取失败，不再显示正在读取。字数规则未就绪时发布始终禁用。没有验证 provider 时验证按钮禁用，不能退回反复原生重试。回复字数校验仍遵循[回帖规则](2026-10-10-reply-length.md)。

设置的“网络”分组在 DNS over HTTPS 下提供“手动过盾”，通过同一个 LocalWebVerification 打开现有验证窗口，不要求先遇到接口错误或先登录。该入口传入空的重试回调，完成验证只返回原设置位置，不重新提交操作，也不改变 DoH 或显示偏好。

改动按每批不超过3文件的小单元提交，覆盖网络识别、首页推送、通知推送、验证页、统一入口和Connect独立认证。版本仍为0.1.7/1008，手机保留数据，按用户要求不做账号切换专项验证。

## Related decisions

[DoH 与 ECH 接入](../feature/2026-10-12-doh-network.md)已验证当前非代理 Wi-Fi 上的现有原生会话。共享 Cookie 不保证新出口持续放行，WebView 不接管 ECH，本篇的真实响应识别与原生读取确认规则继续成立。

检索Cloudflare、过盾、challenge、Cookie、User-Agent。[不可访问话题](../../implemented/bug-fix/2026-10-10-unavailable-topic.md)部分重叠并保留404/权限优先级；[账号与登录](../../implemented/feature/2026-10-07-account-and-composer.md)保留共享Cookie及只读会话检查；[应用内网页](../../implemented/feature/2026-10-10-in-app-web-links.md)保留网页内不跳原生的边界；[首页更新](../../implemented/feature/2026-10-08-home-live-refresh.md)与[运行通知](../../implemented/feature/2026-10-10-runtime-local-notifications.md)的后台验证提示由本篇部分更新。其他命中是资源加载或既有协议引用，与独立恢复入口无关。无完全吸收或过时提案。

## Alternatives considered

- 系统浏览器打开/challenge：网页兼容性最好，且用户熟悉；但系统浏览器Cookie不能自动回传WebView和OkHttp，不能完成App会话的恢复，使用App内共享Cookie网页。
- 继续用登录页同时过盾：复用全部界面且代码少；但已登录重定向会绕开专用入口，也无法区分游客验证和账号认证，增加独立验证页。
- 对所有失败统一重试：短暂网络故障更可能自行恢复；但写操作可能已生效，重复提交有副作用，普通权限/格式错误也不应不断请求，只对明确挑战的GET最多重试一次。
- 在回复错误字符串中匹配 Cloudflare：不必调整编辑器状态契约；但错误文案可能变化或由普通网络错误携带，无法可靠区分恢复动作，因此保留原异常并读取结构化 kind。

## Testing

关联用例覆盖普通403/格式错误不误报、一次短暂挑战恢复、连续挑战只请求两次、写请求不重放、重试读取新Cookie；后台错误不覆盖正常列表。验证入口不走/login，刷新位于完成左侧且不使用缓存重新请求/challenge，提示页面不存在时多刷新直到看到盾、完成后点击完成验证；网页完成不自动退出，只有原生确认成功才触发一次重试，失败保留验证页，游客不要求登录。

## Verification evidence

2026-10-16：新增 `EditorVerificationUiTest` 的5项回归先在旧代码全部失败（`artifacts/editor-verification-repro.txt`），直接复现遇盾仍显示重试、缺少验证入口和失败后仍显示读取中。修复后与 `EditorLifecycleUiTest`、`VerificationUiTest` 合计19项真机用例全部通过（`artifacts/editor-verification-fixed-device.txt`）；覆盖回复面板中完成验证后只读规则一次、取消不重试、原生检查失败保留验证页、规则端点仍遇盾时保留验证入口、普通网络错误即使携带相同文案仍走重试，均保留草稿且不自动发布。`ReplyRulesTest` 的3项字数规则用例通过。真机为 RMX2202 / Android 13，显式构建 0.2.6-dev / 2011，同签名 `install -r` 覆盖保留已有数据。测试网页和原生响应均为本地夹具，不证明当前真实 Cloudflare 挑战一定通过。

2026-10-13：设置网络新增手动入口，`VerificationUiTest` 的6项真机夹具用例通过（`artifacts/manual-verification-device.txt`）。新增用例从设置滚动进入验证，检查右上角完成按钮，取消后再次打开并完成，返回时入口仍在原滚动位置；只请求一次原生列表，不改变偏好、不跳登录或 DoH 页面。显式 0.2.3 / 2005 构建，同签名覆盖安装，保留数据。该夹具不访问真实 Cloudflare。

同日实际登录的 RMX2202 / Android 13 从设置网络“手动过盾”打开生产 VerificationHost，核对“网页验证”、刷新图标和右上角“完成验证”，返回设置。截图 `artifacts/live-manual-verification.png` 与布局 `live-manual-verification-layout.json`；此时 `/challenge` 显示站点的不存在页面，未触发新挑战，不能据此声称真实盾已完成。三个功能完成后的 Lint 再次通过（`artifacts/current-features-lint.txt`），签名和0.2.3 / 2005保持一致，首次安装时间未变。

DiscourseClientTest先复现17项中的4项失败（artifacts/cloudflare-client-repro-20261011.txt），修复后17项全部通过（artifacts/cloudflare-client-fixed-20261011.txt）。覆盖普通HTML403、登录Turnstile、文章引用及常规jsd脚本不误报；短暂挑战重试成功并读取新Cookie；持续挑战最多两次；POST遇盾不重放。

首页推送用例先复现正常列表被写入VERIFICATION错误（artifacts/cloudflare-feed-repro-20261011.txt）。接收独立推送失败时只暂停订阅，正常话题及分页不变，主站读取错误仍走既有错误状态。

通知推送用例先复现独立后台失败产生主站过盾提示（artifacts/cloudflare-notification-repro-20261011.txt），通知流验证失败仅停止当前订阅，后续会话检查允许重新订阅，不更改登录用户或未读计数。

新增验证页界面契约最初因缺少该能力编译失败（artifacts/cloudflare-verification-ui-contract-20261011.txt）。第4项统一入口用例随后在旧ErrorState上复现仍走登录、验证页未显示（artifacts/cloudflare-verification-ui-repro-20261011.txt）。接入统一入口后修复。夹具仅使用本地静态HTML和OkHttp拦截器，不访问真实验证页或修改社区数据。

统一入口接入后，57项关联JVM用例、Debug/AndroidTest构建及Lint通过（artifacts/cloudflare-integration-build-20261011.txt，Lint为0错误、38警告、1提示）；10项离线界面回归全部通过（artifacts/cloudflare-verification-ui-fixed-20261011.txt），包含当时的验证页4项、登录WebView1项、普通WebView5项。

更新提示契约后先在旧验证页复现提示不匹配（artifacts/cloudflare-guidance-repro-20261011.txt，1项失败）。对应界面用例同时检查禁用网页缓存，并连续刷新3次确认始终重新访问/challenge。静态夹具改为站点的“该页面不存在或者是一个不公开页面”，仅验证人工操作引导，不根据网页文字自动结束验证。修复后Debug/AndroidTest构建通过，核对现有手机签名一致并install -r后4项验证页用例全部通过（artifacts/cloudflare-guidance-fixed-20261011.txt），保留设备数据。

Connect真实页面组件的离线界面用例先复现：独立服务挑战时被全局主站验证动作截走，没有打开Connect网页（artifacts/cloudflare-connect-repro-20261011.txt，1项失败）。修复只在等级页ErrorState周围取消主站验证provider，不扩大网络错误类型或自动重放操作。最终5项验证界面用例全部通过（artifacts/cloudflare-final-ui-20261011.txt）；其中Connect用例确认独立网页打开1次、主站验证动作0次。ConnectDataTest的4项数据回归也再次通过。

最终Debug、AndroidTest、Release构建及Lint通过（artifacts/cloudflare-final-build-20261011.txt）；Lint保持0错误、38警告、1提示。正式包为artifacts/release/0.1.7/kodo_0.1.7_release_arm64-v8a.apk，版本0.1.7/1008、仅arm64-v8a、非debuggable。apksigner和16KB zipalign通过，正式证书SHA256为457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418，与既有正式签名一致；APK为4,049,060字节，SHA256为445b369dd6ff4dfa33aaba1e3c9e7cc97884e7185250b05aacdb2adb67daebd1（artifacts/cloudflare-final-package-20261011.txt）。该本地包包含本篇全部修复，未打新Tag或发布GitHub Release。

## Consequences

Cloudflare仍可能因出口IP或设备/客户端特征再次验证，修复误判和会话恢复不能保证站点永久放行。明确挑战最多增加600ms及一次GET；后台订阅暂停期间没有实时提示，重新激活后恢复。验证/latest.json确认主站原生读取恢复，具体受保护端点仍以原页面重试结果为准。全屏验证覆盖期间必须阻挡底层交互及阅读曝光，关闭后保留原导航和数据。

LOAD_NO_CACHE让用户刷新重新访问网络，代价是验证页不复用缓存。最终真机验证使用同签名Debug包并install -r覆盖安装，未卸载或清除数据。离线夹具证明流程及边界，不证明当前真实Cloudflare挑战一定完成，真实站点过盾体验由用户继续验收。
