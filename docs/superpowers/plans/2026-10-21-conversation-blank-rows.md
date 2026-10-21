# AI 沟通空白列表项修复计划

**目标：**隐藏工具消息和成功读取回执不再累积空白，保持可见正文、错误/写入回执和历史阅读行为。

**已确认方案：**此前检查通过外部 Compose 测试复现五轮读取新增十个隐藏项、120dp 间距；用户现要求修复该问题。使用当前 checkout 的独立分支实施。

**归属与复用：**仅调整 ui/feature/conversation 的展示投影和现有 LazyColumn；继续消费原 ConversationState、ReceiptGroup 和 Markdown。保留后端协议历史、读写执行、持久化及订阅，不改网络接收方式。

## 实施与验证

- [x] 新增 ConversationBlankRowsTest：通过真实 Compose 布局比较用户消息与 pending 的位置；每次加入隐藏工具往返/空白正文时位置不变；错误和写入回执仍可见且去重；最终回复保持可见。
- [x] 在未修改生产代码时运行 `gradlew.bat :ui:testDebugUnitTest --tests com.greatwall.callring.ui.app.ConversationBlankRowsTest --console=plain`，确认空白增长断言失败。
- [x] 在创建 LazyColumn.item 前统一投影可见正文和回执；两者皆空时不产生 row。正文使用 isNotBlank，保留非空正文的原始内容。
- [x] latestIndex 按实际可见消息、额外回执和 pending 数计算；自动滚动依赖展示投影。保留原消息下标作为 key/tag，回执仍位于最终回复之前。
- [x] 运行 ConversationBlankRowsTest 与既有 Conversation*、MarkdownCompatibilityTest，检查历史阅读、长内容滚动、复制、回执与输入状态。
- [x] 更新 README 和验收证据；执行 `python tools/verify_backend.py` 与 `git diff --check`。
- [x] 按 requesting-code-review 做独立只读审查，解决必须修复问题。
交付动作：仅提交本次文件；合并 main 后复验，推送并确认该提交的远端 CI。最终提交和 CI 结果以交付回复为准。保留已有用户改动，不默认安装或发布 APK。
