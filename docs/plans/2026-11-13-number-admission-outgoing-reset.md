# 号码统一准入、呼出同步与应用内档案重置

用户已批准本计划并授权实施。保留录音默认勾选；设备源数据严格只读。

## 范围与顺序

- [x] 统一号码规则准入：来电、呼出、历史同步、自动录音发现复用正式 matcher；空规则拒绝全部；拒绝发生在任何档案、通话、来源登记、音频复制之前。匹配历史通话及对应录音不限天数，录音提交复核批次规则。
- [x] 呼出隔离：明确 INCOMING/OUTGOING，旧记录迁移为 INCOMING；呼出 counted=false，不进入来电、打扰统计，不触发自动转写、分析或补档。通话与录音关联保持唯一、方向正确。新增独立录音方向为 UNKNOWN，先保留音频，唯一关联确认来电后才允许自动处理。
- [x] 临时全量清空：设置的数据管理预览全部档案、应用内通话、组件绑定及录音影响；二次确认可选择保留应用内录音、转写、分析，默认勾选。
- [x] 清空一致性：取消并排空旧写入，隔离迟到结果，跨库和文件清理须可恢复；仅限应用私有资源。保留音频时维持稳定来源与归档身份，清除旧核心关联并在重同步时重新关联，不重复模型调用。本次操作不产生历史重导入阻断标记。
- [x] 回归与审查：先验证新增测试确实失败，再实现；覆盖空规则、规则外零入库、旧库迁移、呼出零计分、录音不误关联、两种清空选项、冲突、并发、重启与重同步、幂等。
- [x] 执行统一验证、隔离后端设备验证、源数据前后核对，修订 PROJECT.md 与 README.md；执行证据在本轮 QA 目录。

提交、合并、推送及对应 CI 核对按 PROJECT.md 的交付流程执行，实际结果记录在最终交付回执中。新增两项真机 UI 测试仅在设备手动解锁后运行；默认勾选和状态恢复已有 JVM 验证。

## 现有入口与职责

- `backend/application/calling/CallLogImportService`、`IncomingCallService`：业务准入与事实保存。
- `app/platform/calling/AndroidCallLogSource`：只读系统通话事实；方向映射。
- `backend/application/recording/SystemRecordingDiscovery`：自动发现准入、唯一关联及提交复核。
- 核心 schema、通话 DTO 与投影、列表：方向持久化、旧库兼容及展示。
- `contracts/storage`、`backend/application/storage`、`ui/feature/storage`：清空契约、预览确认与设置入口。
- `BackendGraph`、`BackupGraphOwner`、`RestoreJournal`：复用既有 owner 替换和持久恢复机制，避免另一套全局生命周期流程。

## 验证边界

完整门使用 `python tools/verify_backend.py`。设备仅使用 `com.greatwall.callring.backendqa` 与独立 UI 测试包，不覆盖生产安装、不清生产数据、不改主包权限或角色。真实通话记录、联系人、原录音只查询或读取；无付费模型调用。私人快照和日志仅保留于 gitignored 的 `app/build/qa/number-admission-reset/`。

工作区原有 `.superpowers/`、`docs/plans/layer-boundaries-evidence/` 改动不属于本轮，须保留。
