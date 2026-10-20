# T10b2b 录音业务与媒体边界准备（非验收）

2026-10-21。负责人只读检查 b2a 候选；b2a 规格修复尚在进行，本文件不表示 b2a/b2b 已完成。

## 本片闭包

backend RecordingService 是唯一 RecordingApi 实现与 command binding，拥有身份解析、导入前置和最终 generation/tombstone 验证、attach/retry/delete/play/stop 编排、自动发现匹配和异常到 ApiError 的映射。复用 RecordingWorkflow、RecordingArchiveCommands、RecordingRegistrationService、AiSettingsService，不能再实现第二套归档提交或状态机。

app 提供明确的数据库读取、音频文件、目录授权、播放器、调度、录制组件控制能力；AndroidRecordingRuntime 不保留 implements RecordingApi、bound 转发或旧业务方法。可改为实际平台资源及生命周期持有者，但其每个公开成员必须有真实消费者。具体组合由 RecordingAssembly 构建，同一个 coordinator、workflow、store、player 生命周期；不引入 Runtime2/万能 Dependencies。Graph、Scoped/CompleteSnapshot、CallCaptureService、RecordingJobService 和全部测试同步接新对象，不能为减少测试修改留下旧门面。b2a 删除的无消费者查询接口不因未来可能使用而保留；b2b 按真实方法需求定义窄接口。

## 必须保留或补齐的语义

- 负独立 event ID 优先，正 ID 查 link、目标 archive metadata、原核心 call、最终 link，保持现行顺序和历史正 ID 支持。getArchive 无身份时沿用目标 ID；getIdentity 必须有真实身份。只查目标闭包，不退回全状态或全归档。
- 导入核对调用者给出的号码/开始时间，content URI、256MiB 上限、流复制取消、fsync、metadata 验证由 app 执行。准备在 fence 外；最终复核 generation、command、自动导入 tombstone，才 rename/attach。取消、rename/DB/CAS 失败清新文件并保留旧持久路径；成功后清旧 owned 文件，不把已经持久的变化当失败未发布。opaque prepared token 不泄漏 File 到 backend。
- capture 仅允许 owned 路径；tombstone 阻止自动恢复，失败可重试，不在失败后留下指向已删除新音频的归档。noteMissing/captureFailure 保留既有代次和错误规则。
- 播放 prepare 在 fence 外；最终 ticket、目标 generation、command 检查在同一提交边界；失效候选释放。播放替换、stop、自然完成及错误只按实际 player 事实发布 runtime:recordingPlayback；重复 stop 零发布但成功记录 command。失败时若旧播放器已停止，仅发布真实停止，不伪造 archive 变化。native callback 释放播放器锁再取得 coordinator，避免反向锁序。
- delete 先 owned 音频后归档/tombstone；文件删除失败保留路径供重试。真实停止与归档实际提交合并一个 marker；归档失败仅发布实际停止。继续保留 b2a 对 persist 后 active=false 不丢 publication 的行为。
- 自动发现保留 12h 窗口、现行 filename 原号码边界、yyyyMMdd[_-]HHmmss 本地时区和 ±3s，双方唯一匹配、MICROPHONE 可替换及已删除跳过。未知/歧义文件继续可人工选择；不可借结构整理更改算法或吞掉 Job 的后续 processing。
- folder 授权/列出/inspect 属平台，folder 设置只经唯一 AiSettingsService；非配置文件不产生假目录事实。调度和 stopCapture 不在持锁网络/复制中执行。

## 验证与删除门

用 backend 的窄 fake 验证真实业务分支及平台调用顺序；app 的真实 SQLite、文件、MediaPlayer 测试验证 rollback/CAS、owned 文件、prepare 竞态和自然结束。保留 b1 的 1k/10k 读取范围、cold 4KiB、分页和 D16 完整索引定义。现有 CommandContext、IndependentRecordingRuntime、Publication、FolderView、ToolBusiness、NumberDeletion、ScopedQuery、Snapshot 和组件回归必须跟随真实边界迁移，不能改弱 stale/conflict/cancel 断言。

实施者为唯一 Gradle owner；完成完整统一门和 backendQa/UI AndroidTest 编译后冻结，规格审查再质量审查。记录实际执行与缓存 XML、lint、访问计数和删除清单。b3 的组合删除/身份采用与 c 的总装配仍各自收口，不以此片关闭 T10。
