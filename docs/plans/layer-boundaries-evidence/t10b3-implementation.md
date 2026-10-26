# T10b3 唯一组合删除与身份采用实施证据

2026-10-21。基线 `119e2082846917c168991bb771960ece7a4286d4`；工作树 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`、分支 `codex/layer-boundaries`。仅关闭组合删除、身份采用及其最后旧消费者；未改 primary、版本、冻结协议、schema/JSON/算法/组件身份，未 stage/commit/push 或操作设备。

## 唯一业务与删除项

- backend `application/callers/NumberDeletionService` 是 CallringBackend、commandApi、Graph/UI/81工具的唯一组合删除实现。构造器只接受核心影响读取/事务删除函数、目标录音metadata/代次删除能力、目标已保存绑定读取/复核删除函数及共同coordinator。录音、核心、绑定在各自最终提交fence继续同一个 `continueOwnCommits` 命令；不在yield后capture自有提交。
- 删除 app `platform/callers/NumberDeletionService.kt`、backend `CoreNumberDeletionApi.kt`，退出 archiveCount=0/unsupported 的第二业务。绑定新增 `WidgetBindingStore.bindingsForNumber`，AndroidPreferences只枚举既有SharedPreferences最小键/号码值、返回目标绑定，不访问installed widgets或listing DTO；任意分隔与 `Char.isDigit` 匹配保持。最终 `removeStoredBinding` 复核整个预期binding再持久化/发布/record。
- backend `recording/IdentityAdoptionService` 仅依赖目标archive、editor读取和既有dirty-merge profile提交函数。删除 `SharedBusinessCommands.kt`，真实仍有消费者的 `requiredValue` 独立为 RequiredValue.kt。只采用analysis.name/organization，空白保留旧值，不采用inference/position；错误generation在editor前拒绝。
- Graph退出UUID/continueOwnCommits/SharedBusinessCommands编排，只惰性提供真实录音资源。CallringBackend显式必需录音能力、目标archive函数和operationActive生命周期；测试显式提供测试目录内空资源，生产没有默认空录音或Unavailable组合能力。删除无消费者生产 `UnavailableNumberDeletionApi` / `UnavailableUiEditsApi`。

## 计划、一致性与部分成功

组合服务在任何读取前验证传入command，创建或沿用命令，补齐normalized目标profile/calls及设计要求的recording/preferences集合scope；保留调用方更旧scope。GUI同样捕获基线和caller取消。预览只读核心目标、b1号码metadata及平台音频size，读取后复核；保留profileExists/callCount/archiveCount/audioBytes/widgetBindingCount/busy。NOT_FOUND、三expected计数冲突、QUEUED/TRANSCRIBING/ANALYZING与错误key保持，归档/绑定残余可删。

删除顺序为started倒序/id升序archive→核心单事务→绑定。录音最终fence复核计划generation；绑定阶段重新读取目标集合并与原计划比较，逐条最终fence复核预期binding。首个失败立即停，成功归档保留真实删除/发布且重试只读剩余项，不声称跨文件/数据库原子。外来同号码profile/newcall/newarchive/widget改绑在任何阶段不能被旧命令误删；无关score/core提交不增加新的全core依赖。

身份采用在读前marker保护归档依赖：canonical负ID只保护archive实体，正legacy/statistics alias还保护必要recording集合。档案scope使用NumberIdentity规范化号码和同一读前marker，保护exists/name/department；其它字段通过唯一caller dirtymerge保留。最终caller guard复核archive/profile scope及外部command/caller/Graph关闭状态，读取旧analysis后归档变化不能授权profile写入。最终持久提交后的publication/record仍在既有fence中完成。

## Meaningful RED/GREEN

| 回归 | 实际RED日志 | 最终专项 |
|---|---|---|
| archive读取后替换拒绝profile提交 | t10b3-identity-red.log，2例1失败 | GREEN |
| 调用方只给profile scope，归档删除后新成员仍必须拒绝 | t10b3-scope-red.log，3例1失败 | GREEN |
| 同计数同ID归档generation替换不可误删 | t10b3-generation-red.log，1例1失败 | GREEN |
| 无关score提交继续删除；canonical采用忽略无关归档且dirtymerge whitelist | t10b3-scope-precision-red.log，5例2失败 | GREEN |
| 格式化archive号码必须保护normalized profile | t10b3-identity-format-red.log，1例1失败 | GREEN |

迁原集成测试并将并发hook移动到新窄读取点，保留archive失败顺序/停止、archive后foreign profile、第二次绑定读取后改绑、集合scope、owned音频失败重试等原行为断言。新增GUI四种foreign提交、generation实体复核、窄fake计数/三busy/NOT_FOUND/顺序部分失败重试、generation零editor读、关闭owner后最终拒绝。未用删除断言替代迁移。

## 访问范围与限制

真实组合预览夹具1k/10k无关归档及绑定：录音warm号码投影1001/10001，目标metadata/payload各1，full/list archive decode各0；音频73B。目标大transcript通过b1有界metadata流跳过，实际sourceBytes为1400476/1400477、最大块4096B，不把源字节称为常量或全量解码。SharedPreferences既有布局必须getAll，实际单次枚举1002/10002个存储键值（含ASCII格式变体和Arabic数字号码）；目标返回1、installedWidgetReads0。两种数字码点不混同。安全JSON来自本次测试实际输出 `app/build/qa/layer-boundaries/t10b3-number-access-{1000,10000}.json`，不改oracle凑数。

完整门还保留b1历史cold/warm、分页及核心call范围回归；历史metadata投影流访问限制保持。没有为本片改变schema/JSON或把未全文索引的metadata标成D16完整。

## 冻结与验证

生产/测试源码27路径候选SHA256 `5b3569ef358d597383ddb4f636ec2e121dee5fde3dc37c7cf8f3caa3bb481979`。清单仅在ignored `build/qa/layer-boundaries/t10b3-source-candidate.json`：基线diff `--no-renames --name-only`与untracked .kt/.java并集，相对path排序，逐项path+LF+rawbytes（删除为DELETED）+LF。tracked diff及untracked源码EOF/尾空白已检查通过。最终专项 `t10b3-final-focused.log` BUILD SUCCESSFUL 24s，backend实际执行、app同源码专项UP-TO-DATE，未称全量。

完整统一门实际exit0，1m57s、147任务16执行/131缓存。backend343与app321实际全量执行，UI298沿用缓存XML，失败/错误/跳过全0；contracts:test为NO-SOURCE。assembleDebug和backendQa编译实际执行，UI AndroidTest编译缓存成功。app lint analysis实际执行而report沿用缓存，UI lint analysis/report缓存；Fatal/Error0，UI31Warning+3Hint、app6Warning。XML来源、时间范围、聚合SHA与task状态均见[t10b3-verification-summary.json](t10b3-verification-summary.json)，完整主门日志SHA256 `21f8e23ae4b2572eabc4ce6651f672824a7c9ec96157aa60f9d2f55307362cb7`；ignored t10b3-verification.json SHA256 `1b7822c09a1a0a152d8aa435289b6cd9daf81b0def5507787d8890219973e897`。两Python守卫及17/4负例实际运行均exit0，114 JVM源依赖及冻结库存通过，日志 `build/qa/layer-boundaries/t10b3-architecture.log`。源码冻结交规格→质量双审；没有设备，A9未验证。

T10c仍有BackendContext、总装配及CallringBackend其它真实Unavailable消费者；T11旧feed、T13全状态初始化/fixture继续明确后续门。本片不进入这些任务，未保留本片旧业务/别名/转发/default空能力。
