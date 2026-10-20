package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.ai.*

private val purposes=listOf(
    "验证号码合法性/规范号码", "读取首页概览", "检索档案分页", "读取档案编辑字段/职位选项", "读取号码通话概览", "读取最近通话",
    "保存号码档案，number必填，name/department/positionId/whitelisted按字段修改；合法号码，不覆盖未指定字段", "修改号码白名单enabled",
    "读取号码报表，日期ISO/zoneId/分页，权威总量", "导出CSV到系统所选文件", "读取设置摘要", "启停通话统计enabled", "启停悬浮窗enabled",
    "读取免打扰规则", "保存免打扰mode/allNumbers/weeklyWindows，分钟0..1440", "设置免打扰模式OFF/NON_WORK/CUSTOM", "启停免打扰",
    "读取职位与本人职位", "保存职位id/name/level正整数/active", "删除职位，先确认及引用检查", "设置本人职位id可null清除",
    "读取当前/历史排班", "保存排班zoneId/weekly星期1..7/makeup分钟窗口", "重置排班", "读取存储日历来源及年份", "同步指定year日历，仅排队或真实回执", "系统选择并导入日历文件",
    "保存date日期覆盖REST/WORK及windows", "删除date日期覆盖", "读取请假分页", "保存请假id/period/note，ISO日期或epoch毫秒", "删除请假须本地确认",
    "读取评分规则", "修改评分权重offHoursWeight/repeatWeight与repeatMinutes/observationDays", "重置评分规则", "读取外观设置",
    "修改深色模式/主题：colorMode枚举FOLLOW_SYSTEM/LIGHT/DARK；selection为PRESET/CUSTOM，presetId预设，customSeedRgb为24位RGB。深色必须colorMode=DARK，不存在darkMode字段",
    "读取铃声设置", "启停铃声enabled", "系统选择导入铃声音频", "恢复内置铃声", "启动铃声预览，LAUNCHED不是播放完成", "停止铃声播放",
    "读取权限/角色/电话运行状态", "读取已存安全诊断", "请求同步通话记录，仅QUEUED", "读取widgetId绑定", "保存widgetId与number绑定", "删除widgetId绑定",
    "录音归档元数据分页，不含全部全文", "读取录音配置安全视图", "合并保存录音/AI配置，保未改字段/密钥", "本地确认后测试已存连接slot，可能计费",
    "读取callId录音归档及全文引用", "系统选择文件或handle导入录音到callId", "重试callId转写分析，仅QUEUED", "确认并删除callId归档", "播放callId录音", "停止录音播放",
    "系统选择已授权录音目录", "有界枚举已授权目录文件handle", "读取持久小组件绑定及实际安装", "确认采纳归档身份到档案",
    "读取AI安全配置与keyConfigured", "patch AI配置/预算/兼容方式，保密钥", "系统本地编辑指定slot密钥，不在聊天收集", "系统请求capability权限/角色", "打开系统应用设置仅LAUNCHED", "系统请求固定小组件", "系统测试悬浮窗", "预览callId来电界面", "本地确认复制from/to连接，转写到分析会重置AUTO并取消旧会话"
)
fun toolPurpose(index:Int,name:String)=purposes.getOrNull(index-1)?:name

data class DiscoveryPage(val tools:List<ToolDefinition>,val nextCursor:String?,val complete:Boolean,val catalogVersion:String="2") {
    fun json()=aiObject("catalogVersion" to aiString(catalogVersion),"tools" to AiJson.Arr(tools.map{aiObject("id" to aiString(it.id),"name" to aiString(it.name),"description" to aiString(it.description),"write" to aiBool(it.write),"schema" to it.schema)}),"nextCursor" to (nextCursor?.let(::aiString)?:AiJson.Null),"complete" to aiBool(complete))
}
class CapabilityCatalog(private val registry:ToolRegistry) {
    fun discover(category:String?=null,exactNames:List<String> = emptyList(),query:String?=null,cursor:String?=null,limit:Int=8):DiscoveryPage {
        require(limit in 1..12)
        val terms=query.orEmpty().trim().lowercase().split(Regex("\\s+")).filter{it.isNotEmpty()}
        val names=exactNames.toSet()
        val rows=registry.allDefinitions.filter{d->
            (category.isNullOrBlank()||category=="all"||d.name.substringBefore('_').equals(category,true)||d.description.contains(category)) &&
            (names.isEmpty()||d.name in names) && (terms.isEmpty()||terms.any{d.name.lowercase().contains(it)||d.description.lowercase().contains(it)})
        }
        val offset=cursor?.removePrefix("catalog2:")?.toIntOrNull()?:if(cursor==null)0 else error("ai.cursor_stale")
        require(offset in 0..rows.size)
        val page=rows.drop(offset).take(limit);val end=offset+page.size
        return DiscoveryPage(page,if(end<rows.size) "catalog2:$end" else null,end==rows.size)
    }
}
