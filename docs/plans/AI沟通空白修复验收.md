# AI 沟通空白修复验收

## 原因与范围

工具调用及其成功读取回执被保留在 ConversationState.messages。旧 ConversationScreen 隐藏正文/回执内容，却仍为每条消息创建 LazyColumn.item；每个空项累积 12dp 列表间距。此前临时 Compose 测试实测五轮读取、十个隐藏项使等待提示下移 120dp。

本次仅改 UI 展示投影：在创建 item 前计算可见正文和回执，两者均为空时跳过；纯空白正文使用 isNotBlank 判断，正常正文保持原样。保留原消息下标作为 key/tag，latestIndex 改为可见项数量，自动滚动依赖实际展示内容。后端协议历史、工具执行、业务读写、持久化、订阅及网络完整接收模式不变。

## 回归验证

- 正式回归先于生产修改运行：隐藏工具往返第一轮新增 24dp，纯空白消息撑高列表，两项不增长断言均失败。
- 修改后 ConversationBlankRowsTest 的 3 项通过：连续五轮隐藏工具消息位置不变；空白/空正文位置不变；错误回执、已核验写入和最终回复保持可见，配对回执不重复，原消息 key/tag 保留。
- Conversation* 与 MarkdownCompatibilityTest 共 27 项定向测试通过。Markdown 异步解析后才有最终高度，新增最终回复检查和既有“回到最新”检查按同文件长表格测试的 waitUntil 模式等待实际可见，不把首帧当作最终布局。
- 首次正式红测前的跨盘临时测试增量缓存错误，通过 Gradle 停止 daemon 和 :ui:clean 清理生成产物；编译错误不计入回归断言失败证据。未改构建配置。

## 交付与边界

统一验证 `python tools/verify_backend.py` 通过：backend 369 / UI 314 / app 361 项 JVM 测试（共 1044 项，零失败/错误），两架构守卫与三组 Python 测试、真实 UI 编译类路径、Debug/Release/backendQa/双 AndroidTest APK 和双 lint。contracts 无测试源码，单列 NO-SOURCE。独立只读审查未发现必须修复问题，确认回执谓词、消息 key/tag、滚动索引、历史保护以及后端新列表发布方式均保持正确。

构建证据：app/build/qa/backend-refactor/summary.json 及其引用的日志、JVM XML 与 lint 报告。仅提交本次 UI、测试和文档改动，保留用户既有工作区文件。合并推送及远端 CI 的最终提交和结果以本次交付回复为准。

此次未进行真机安装、真实模型调用或设备测试；不宣称已更新手机上现有安装包。需要分发 APK 时按发行规则确定新版本。
