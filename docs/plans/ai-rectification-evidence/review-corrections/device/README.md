# 新 APK 只读真机复验

准确代码提交及安装 APK SHA-256 见 build.json，本地 APK 与设备 base.apk 哈希相同。没有发送模型请求、执行新增批次、修改已有档案/外观/预算/凭据；未卸载或清数据。

三个类各在新进程独立执行并报告 OK (1 test)：
- AiConversationPersistenceDeviceTest：原十条合成档案、DARK 重启保留，聊天不持久化。
- AiConversationSyntheticUiDeviceTest：搜索合成档案、编辑页姓名/号码实际显示；未点击保存。
- AiConversationPresentationDeviceTest：默认目标只含 model/host，真实触摸打开服务详情，实际无障碍节点内容与安全配置视图完全一致；触摸输入框，WindowInsets 确认实际 IME 可见；模型 exchange/回执为空。

首次把三个类放在同一次 instrumentation 时，持久化通过后列表阶段未返回，之后主动停止进程，原日志保留 initial-combined-blocked.log；这是失败/中止记录，不能按成功统计。加入无业务值阶段轨迹后，列表类独立进程4.192秒通过，presentation3.659秒、persistence2.393秒通过。尚未确认首次连续运行卡住的根因，未重复同样失败路径来扩大业务影响。

截图只取本应用 Activity 的 PixelCopy surface，不包含系统 IME、Compose Dialog 或其他应用。compact-keyboard.png 显示输入区域实际避让，actualImeVisible 由真实 WindowInsets 断言，图片不声称显示键盘本体。连接详情由真实触摸后的界面节点核对，不用未包含 Dialog 的 Activity 截图冒充详情图。所有交付图片已逐张检查，无其他应用悬浮内容。Activity 结束即恢复正常亮屏策略，未改系统超时设置。

这是新版本只读 UI/持久化证据，原付费业务执行仍绑定旧346e896 APK；不是新真实模型验收。
