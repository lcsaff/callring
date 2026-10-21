# T13b2b UI职责整理候选

基线 `913794b5e4d6727a762ab8826cc2038a2a139277`。仅本片UI职责及精确D8守卫；未提交、未推送、未执行设备门。

ThemeDefinition的配色算法、contrast、neutral surfaces与brand fills保持。designsystem唯一`CallringTheme(spec: ThemeSpec, content)`不再解释偏好；完整ThemeRegistry/DefaultAppearance/偏好映射归`ui/app/bridge/AppearanceThemeMapper.kt`，唯一业务转换`toThemeSpec`。Compose映射缓存沿原registry/selection/presetId/customSeedRgb键保留palette身份，colorMode/systemDark仅选深浅；widget仍读原resources.configuration。未知preset、无效seed显示fallback不写回，AppearanceControls的CUSTOM+seed、hex draft/debounce保持。旧designsystem入口/overload、测试专用registry companion便利转发删除。

CallerCard连同CallerProfile/UiFormat归feature/shared/CallerComponents；rememberFormBack连同MutationState与预测返回协程编排归feature/shared/FormFeedback。designsystem内唯一internal UnsavedChangesDialog供FormDialog及feature共享，没有反向依赖。MutationSuccessEffect保持原纯视觉owner。

RecordingScreens.kt、RecordingViewModels.kt、SettingsViewModels.kt彻底删除。三个实际screen、两个完整VM（含private Read）、七个实际settings VM各归文件；status/feedback/recordingMessage共同归RecordingPresentation。构造器、订阅、canonical identity、player退出清理、routes、lifecycle、draft及existing ScopedRecordingSettingsViewModel归属未改。

D8守卫仅检查main designsystem的contracts/state/feature/app依赖、已删主题/CallerCard/rememberFormBack入口及旧渲染签名。16项回流负例先失败，新增3组测试后architecture共36项通过；backend guard原5项通过。合法ThemeSpec/MutationSuccessEffect/Map/Any视觉正例保留，不扩为T14全部规则。

## 验证

新mapper测试先在旧源码确认bridge新API缺失的编译RED（`t13b2b-red.log`），随后定向ThemeTest/AppearanceThemeMapperTest/InteractionJvmTest及UI AndroidTest编译GREEN。mapper覆盖所有mode×systemDark、未知preset、invalid/missing/custom两端seed、偏好不变、registry顺序/RGB/可扩展不可变快照、重复ID/缺默认拒绝。原5项偏好/registry测试合并迁入2项matrix测试；visual算法断言完整保留。额外表单直接回归覆盖clean返回、dirty取消/丢弃、busy阻止、失败不离开、成功token仅离开一次；生产返回逻辑为原body机械迁移。

唯一完整门包括contracts/backend/UI/app JVM、debug assemble、UI/app lint、backendQa、UI/app AndroidTest Kotlin与Release Kotlin/Java。`t13b2b-fullgate.log`：exit 0，2m15s，186 tasks（41执行、145 up-to-date）。UI310/app335实际执行，backend363为前片缓存XML；总1008测试0失败/错误/跳过，contracts NO-SOURCE。UI lint 30 Warning+3 Hint，app 6 Warning，无Error。UI AndroidTest定向已实编译，完整门up-to-date；app AndroidTest NO-SOURCE。Release Kotlin实执行、Java up-to-date。Gradle弃用提示如实留日志。两Python guard通过，backend当前140 JVM源；git diff --check及untracked EOF/尾空白通过。

源码冻结：65个变动/新增/删除kt/java路径，按sorted relative posix path+LF+raw bytes（删除为DELETED）+LF，SHA-256 `a9364d9a2e67e02186847f191feda555565ca9e2cce71413dcefbe58689313f2`。manifest `build/qa/layer-boundaries/t13b2b-source-freeze.json` SHA-256 `27fc418b883a49eadc9ebd84345b319e29d7df5d27bad71a47440addf648785b`；完整门后重算相同。完整日志/XML aggregate（WindowsPath排序→relative posix+LF+raw XML+LF）、时间、实际/缓存状态、lint severity/hash及guard源码hash见[t13b2b summary](t13b2b-verification-summary.json)。

backend fixture和43组SQL历史样本未变，不复制；来源沿用[t13b2a实施证据](t13b2a-implementation.md)及其summary。原227历史文件列保留并回填本片真实target；当前354 main文件一一归属（app86/backend140/contracts23/ui105）。

T13b2c六系统组件controller、PROJECT/README和最终清单复核，T14统一CI、T15最终设备验收仍待办。本候选等待规格/质量审查，不宣称T13整体完成。


## EOF规范化增量

负责人staged diff --check发现12个新文件的空白EOF行：先前untracked检查仅验证存在末尾换行及尾空格，没有检测多余EOF空行，原“EOF通过”口径不完整。现仅裁去这12个文件各一个CRLF空行（共24字节），每个文件保留原CRLF及全部body字节；与git show :path的LF-normalized staged内容比较，唯一差异也是末尾空行删除。完整BASE diff --check（含staged新增）及显式末尾blank-line字节检查通过。

实际完整门testedSourceSha256仍为`a9364d9a2e67e02186847f191feda555565ca9e2cce71413dcefbe58689313f2`，不声称新字节再次执行了Gradle。最终65路径source SHA为`c3c73d68795a7bdce0add75663d71a688cd290adbfaac87b655b05ce11687e2f`，manifest SHA为`ad75c7b7c0585b4c2e379871bc28124e76cf4c4b1f0eab8ad955525159a1db8e`。仅EOF增量证据`t13b2b-eof-delta.json` SHA为`3637ac2330e075126425a79d1dce7b087e05c461c0ffad825b6417015e98bec2`，记录12文件staged/原working/新working哈希及字节证明；delta审查待负责人收束。未重复Gradle。
