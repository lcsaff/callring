# 锁屏来电显示与录音链路验证计划

**Goal:** 锁屏亮屏时可显示当前来电卡片，检查锁屏录音到归档的实际能力。

**Architecture:** ui/overlay 保留唯一 OverlayController 和 OverlaySessions。服务持有控制器，普通窗口和专用 showWhenLocked Activity 互斥展示并复用 OverlayRoute；Activity 不重复建立业务订阅。息屏不主动点亮；接听、挂断、权限撤回、服务销毁和解锁都按当前会话关闭或切换窗口。backend、存储与订阅协议不变，无数据迁移。

**Tech Stack:** Kotlin / Compose / Android WindowManager / Robolectric / Android instrumentation。

用户已确认锁屏显示现有完整卡片及结束后不重弹。选择专用 Activity 而非仅给普通 overlay 加 flag，是因为 Android 系统可在 keyguard 未被遮挡时隐藏普通窗口。保留原系统组件身份，新增内部非导出 Activity，不使用全屏通知或默认拨号角色。

- [ ] 添加锁屏窗口绑定生命周期、失效及普通窗口切换的测试，执行 `./gradlew.bat :ui:testDebugUnitTest --tests '*LockScreen*'`，记录 RED。
- [ ] 实现 `LockScreenOverlayHost.kt`、`LockScreenOverlayActivity.kt`，在 UI Manifest 注册非导出锁屏 Activity；服务在亮屏、当前来电状态有效且权限具备时发布绑定并启动 Activity，解锁清绑定并恢复普通窗口。Activity 只观察服务绑定，失效即结束。
- [ ] 执行针对性 JVM 测试并观察 GREEN，再执行 `python tools/verify_backend.py` 完整统一门。
- [ ] 在隔离 UI 包验证锁屏窗口、解锁恢复和结束撤销，恢复临时权限；不清除或覆盖用户主包数据。读取设备条件后，运行现有后端设备测试验证录音、转写分析状态机与持久化；真实 SIM、付费服务或需要人工凭据时明确记录未验证，不用模拟结果替代。
- [ ] 更新 README 与验收记录，审查差异，提交范围内变更、合并 main、推送并核对对应 CI；保存原工作区改动。

验收：亮屏锁屏来电显示、接听/结束关闭、解锁无旧摘要、服务关闭不遗留 Activity、后台归档状态持久化。真实锁屏双向采集与实际 AI 服务调用分别记录，不能由编译/模拟测试冒充。
