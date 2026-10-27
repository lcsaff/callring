# Built-in Ringtones Implementation Plan

**Goal:** 提供五首内置铃声和随页面生命周期持续试听。
**Architecture:** contracts 稳定枚举及选择/停止试听能力；backend 统一写入及精准失效；app 保存枚举并绑定真实 raw 资源；ui 单选及生命周期清理。
**Tech Stack:** Kotlin、Compose、Android MediaPlayer、JUnit/Robolectric。

- [x] 修改 SessionGateTest 断言 8 秒和长时间仍播放，并运行 :app:testDebugUnitTest 验证红灯。
- [x] 补 RingtonePreparationTest：所有选项重开可读、默认旧旋律、无变化不发布、选择新铃声使准备中的导入过期；资源由真实播放器读取验证。
- [x] contracts SettingsApi：BuiltInRingtone 五值，restoreBuiltIn 接收默认 MELODY，stopPreview；RingtoneSettings 增加 builtInRingtone，previewSeconds 可空。同步正式端口、全部 Fake、SafeProjection 及审查后的契约摘要。
- [x] AndroidAudioController：枚举偏好解析及回退、资源绑定、原子保存；restore 默认恢复 MELODY。新增四个 WAV 与可复现生成工具。
- [x] SessionGate 去除试听超时；PlaybackServiceController STOP_PREVIEW 仅终止试听，补控制器测试。
- [x] RingtoneViewModel 串行试听/停止及离开时封口；SettingsRoute LifecycleStartEffect；RingtoneScreen 单选与持续试听说明。补 VM 迟到请求与 Compose 生命周期测试。
- [ ] 同步 PROJECT.md、README.md，运行 python tools/verify_backend.py 与 git diff --check。自审后提交、合入 main、推送，核对同 SHA CI；有设备则补隔离验证。

定向验证：SessionGate 的持续试听断言先红后绿；新增空闲停止服务测试先红后绿。资源/持久化/失败回滚/过期导入、ViewModel 迟到启动、Compose 选项及生命周期定向验证通过。Compose disposal 断言等待重组后检查，不使用无 UI 状态等待代替空闲屏障。完整门、推送与 CI 待执行。
