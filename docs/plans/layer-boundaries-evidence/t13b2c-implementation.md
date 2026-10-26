# T13b2c 六系统组件职责闭包

目标：六根组件保留原注册FQCN、Manifest/Job/PendingIntent身份和真正Android资源接线；原工作流主体删除，唯一进入entrypoint/runtime、jobs、recording、calling的六controller。Playback保持Java，所有controller只收窄函数或同资源typed port，没有Graph/Context/Service/完整API、兼容forwarder或第二实现。

关键保留：Runtime IDLE同步调度先于goAsync，串行worker与单try/finally；Calendar上海日期、attempt先commit、返回Failure继续/抛异常终止；Sync旧JobRuns完成不能结束新run、三类异常重试及content先rearm；Recording每start token、取消只影响当前token、finish先于下一调度；Capture同锁/私有worker、destroy同步封口、stop成功后attach、cleanup入队后shutdown；Playback令牌/ready一次、媒体callback身份、1800ms提交门与2400ms清理超时、150ms SessionGate poll及幂等结束。既有PreparationGate/SessionGate/CallStateSnapshot/JobRuns/CalendarRefreshPolicy唯一复用。

新21项测试直接执行实际controller，覆盖顺序、短路、旧完成、取消、失败清理、迟到prepare/deadline/focus和媒体队列；四组RED日志记录新类型尚不存在导致编译失败，GREEN记录实际实现通过，另guard RED为缺规则导致负例断言失败。未把纯delegate镜像或复制算法算测试。精确证据见[t13b2c-verification-summary.json](t13b2c-verification-summary.json)。源码冻结16个kt/java路径，SHA-256 `8d13d936ad7d7e604e66e183c3665f4cd801989478ba3295171096ea89d86bb2`，相对POSIX路径字符串字典序排序（RingPlaybackService在entrypoint前），path+LF+rawbytes+LF；完整路径在summary。XML聚合独立按WindowsPath排序。

完整门一次执行成功（2m18s，186任务：34执行、152 up-to-date）：backend363沿缓存，UI310/app356实际执行，零失败/错误/跳过；contracts:test无源码。Debug APK、双lint、backendQa、Release Kotlin/Java通过；UI AndroidTest Kotlin缓存，app AndroidTest Kotlin无源码。两静态guard及39+5 Python tests通过。沿用[T13b2a归档43安全样本](t13b2a-implementation.md)（含2历史SQL基线），本片无额外归档或复制43样本；当前完整门执行原数据集测试，可能重写raw app/build JSON。没有stage/commit/push/改版本/adb。

最终filemap精确覆盖360 main文件（app92/backend140/contracts23/ui105），保留原227历史行、日期及设计列；六项回填壳+controller。PROJECT/README同步模块与现行命令，legacy ledger关闭本片原工作流。T13代码职责闭合；审查结论/执行状态由负责人记录。T14统一CI与T15设备/最终验收仍pending：真实Telecom/音频焦点/唤醒锁、通知身份和系统采集、厂商后台/锁屏未在设备上验证。
