# T14/T15 CI 与设备验收准备（非验收）

独立只读复核基于 db9b9a2，未运行 Gradle、adb 或修改业务。最终实施按届时实际路径复核。

## CI 与统一命令

- build.yml 的 push 仅 main/feat/**，补实际 codex/** 分支；当前门缺 contracts:test、四个 Python 架构/负例命令、backendQa/app/UI AndroidTest 构建。
- release.yml 统一门还缺 ui:lintDebug、contracts 与架构检查；同 README/PROJECT/两个 verify 脚本统一清单，保留签名 Secrets 和发布开关，不输出密钥。
- 仓库只有 gradlew.bat；Linux CI 已安装 Gradle8.13 后调用 gradle。两个 verify 脚本非 Windows 却选择不存在的 gradlew，须统一真实可执行入口，不让不同入口验证不同内容。
- 统一门包括 JVM、Debug/Release/backendQa/AndroidTest、lint、四个 Python 命令和解析后 UI debugCompileClasspath 无 backend 检查；声明正则不能替代解析类路径。
- workflow 检查器目前验证脚本/参数存在性，须增加实际架构/契约/类路径门的断言。

## backendQa 隔离流程

backendQa APK 自带 android.test.InstrumentationTestRunner，target 是自身 .backendqa 包；不是安装 app-debug-androidTest.apk。BackendQaApplication 使用专用数据库/偏好，必须保留隔离。当前指定四类（目录迁移后同步）：

- com.greatwall.callring.data.SqliteBackendStoreDeviceTest
- com.greatwall.callring.backend.BackendApiDeviceTest
- com.greatwall.callring.backend.BackendMutationDeviceTest
- com.greatwall.callring.BackendSchedulerDeviceTest

verify_backend 需校验单 APK metadata、输出目录内路径、aapt package 与注册 runner target；安装必须 Success，runner 退出成功且 OK(N tests) 的 N>0。设备命令设置超时，只可 force-stop/清理专用 QA 包，不触碰主安装和主角色。失败也在 finally 写本次 status/commit/命令退出码/设备序号，防止遗留上次成功 summary。backend/UI/app 测试计数必须确有执行；contracts 当前 NO-SOURCE 如实单列，不能伪造测试或笼统与业务模块混作成功计数。

两个 verify 脚本的 .resolve()/.absolute() 应统一保留实际工作树路径拼写，避免 C:/D: junction 根混用。设备验收报告区分编译与实测；SIM/待机/重启/升级/下载没有执行就保留未验证。

## 删除守卫

按精确声明/引用与调用规则覆盖生产和测试，并为每项加入违规负例与合法正例：

- BackendContext、旧 BackendStore/BackendState、全量 StorageSession.load/save、coreScopeValues、LazyBackendStore、资源 .legacy 投影。
- DataChange/旧 observe、nullable/default snapshots、同查询双 loader/兼容开关、unsupported 默认装配。
- 全库前后差异、双发布兜底、普通业务调用完整快照，以及工具 numeric index/ordinal 行为分派。

保留合法 sealed/JSON/Keystore 类型处理、纯 URI、codec、显式 operationCode/legacyIndex/displayOrder 数据正例。不得按 legacy/fallback 单词或全部 File 类型一刀切。

ToolRegistry 的展示字面量许可当前依赖数字 index/位置参数，具名目录后改为明确声明解析，不能扩大为目录白名单。当前契约 inventory 同步经过审查的当前源码；冻结 baseline 不改。

主负责人决定：ai-operation-compatibility.json 继续冻结并保留原 pin。具名生产目录内部结构变化不构成重写 golden 或移除 pin 的理由；可增加外部字段语义比较，不能通过更新 hash 掩盖工具名/schema/标识变化。
