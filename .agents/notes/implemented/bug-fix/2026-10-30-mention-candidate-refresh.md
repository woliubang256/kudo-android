# Agent Note: 连续输入提及时刷新候选并简化浮层

Status: implemented

## Problem

用户反馈裸 @ 已有候选，但继续输入字母后列表不变；候选框的液态边缘和背景底色也过于明显。已批准先复现搜索更新，再把候选框改为更厚的半透明模糊，去掉折射边缘。

## Decision

通过生产 EditorForm 与同窗口宿主逐字输入、删除和无结果查询，区分输入词段、网络参数与浮层渲染；真实只读查询同时比较裸 @、ne 和不存在的用户名，不能再用“均有返回”证明搜索正常。回复子组合中出现新词已经请求、浮层仍为旧候选的缺口，按 MentionQuery 重新绑定浮层身份修复，具体契约及搜索验证归入[提及补全](../../implemented/feature/2026-10-29-editor-mentions.md)。旧请求取消和点选前词段校验继续保留。

候选框沿用同窗口兄弟采样与非模态输入契约，仅简化材质：液态和模糊设置下均使用 drawPlainBackdrop 的 24dp 模糊，取消折射、高亮、亮度和饱和度放大。纯半透明设置及 API 31 以下继续无模糊；浅深色采用中性白与深灰，模糊时遮色不透明度统一为 .82，无模糊时为 .94。只保留 3dp 外阴影用于区分浮层，并在采样之前绘制，透明 Surface 自身不再设置 shadowElevation；否则阴影在背景采样之后压入透明表面，产生截图中的厚灰框。不添加说明文字，不改其他工具或弹窗的材质。

## Existing note audit

[提及补全](../../implemented/feature/2026-10-29-editor-mentions.md)继续拥有词段、请求和发布语义，补充连续输入验收。[同窗口材质](../../implemented/bug-fix/2026-10-30-mention-material.md)保留来源隔离、定位、焦点和生命周期，其候选折射选择由本篇部分取代。[编辑工具采样](../../implemented/bug-fix/2026-10-18-editor-tool-sheet-material.md)保持其他工具行为。其他命中为个人中心、通知和工具栏，无同主题旧提案或完全吸收项。

## Alternatives considered

- 在客户端仅过滤首次候选无需再次请求且反馈快，但无法找到不在最初六人中的用户，也不能准确复用服务端的昵称及权限匹配；保留按最新词段请求。
- 保留液态折射能与其他面板保持一致，但本候选框的厚边缘影响输入时的辨识，用户明确选择普通半透明模糊；仅为候选建立例外。

## Testing

- 旧实现的材质复现两项均失败（`artifacts/mention-frosted-red-device.txt`）：LIGHT/LIQUID 红蓝透色差为 .40，超过候选框低透色的 .20 上限；液态相对普通模糊的边缘差为 .145，超过 .025 的相同外观界限。
- 验收同时保留动态来源透色下限、条纹窗口坐标与模糊边界，以及来源不包含候选、外部点击和退出释放；限制透色上限以验证厚度，逐行比较液态/模糊右边缘以验证没有液态高亮和折射，不能只放松原透色下限。
- 首版去掉折射后 11 项回归通过，但人工截图仍看见灰框，说明液态/模糊相同并不足以证明边缘正确。补充均匀背景内侧采样复现：LIGHT/LIQUID 边缘与内部差为 .573，超过 .035（`artifacts/mention-inset-red-device.txt`）；将阴影移到采样前，避免透明 Surface 在采样上方绘制阴影。
- 外阴影修复后的 12 项中 11 项通过，深色透色差 .208 略超 .20 上限（`artifacts/mention-frosted-no-inset-device.txt`），因此把深色遮色由 .8 收紧到与浅色相同的 .82，未放松测试界限。最终材质五项与发帖、窄屏深色大字回复两项共七项全部通过（`artifacts/mention-frosted-delivery-device.txt`，23.307 秒）；合并前批后，本轮 12 个不同 UI 用例均分批通过，真实接口的三种上下文另有一项只读用例通过。
- 人工查看 `artifacts/mention-frosted-delivery-review.png` 的发帖及窄屏深色大字回复，确认厚灰框消失、中文可读、候选不挡光标且在键盘上方；`artifacts/delivery-frosted-mention-material-light-liquid.png`、`artifacts/delivery-frosted-mention-material-dark-translucent.png` 和 `artifacts/delivery-frosted-mention-frosted-*.png` 保留均匀及条纹来源证据。截图为离线 Compose 夹具，真实网络只做查询，没有发帖、私信或通知。
- 最终 Debug / AndroidTest 构建通过（`artifacts/mention-frosted-delivery-build.txt`）。RMX2202 / Android 13 的开发包保持 versionCode=3002、versionName=0.3.1-dev，同签名 adb install -r 保留数据；AndroidTest 使用显式安装及 am instrument，不使用自动管理安装生命周期的任务。
- 最终 lintDebug 通过，0 errors、60 warnings、1 hint（`artifacts/mention-frosted-delivery-lint.txt`），没有增加告警；firstInstallTime 仍为 2026-10-23 14:55:34。后续只整理笔记，不重复已通过的无关测试。

## Consequences

候选框减少背景细节和色彩干扰，代价是液态设置下本候选也使用普通模糊，与其他浮层不再完全一致；这是用户明确要求的局部例外。来源、窗口坐标和正文焦点规则继续有效。服务端排序可能同时匹配用户名和昵称，不能把客户端的简单前缀过滤当作正确性标准。当前真机只有 Android 13，其他系统降级需额外设备验证。
