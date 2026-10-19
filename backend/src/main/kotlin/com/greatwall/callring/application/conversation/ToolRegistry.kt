package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.api.*

data class ToolDefinition(val index: Int, val name: String, val schema: AiJson.Obj, val write: Boolean, val description:String=toolPurpose(index,name)) { val id get() = if(index<=72) "O%02d".format(index) else "X%02d".format(index-72) }
class ToolRegistry {
    private val string = aiObject("type" to aiString("string"))
    private val integer = aiObject("type" to aiString("integer"))
    private val boolean = aiObject("type" to aiString("boolean"))
    private fun nullable(type: AiJson.Obj) = AiJson.Obj(type.fields + ("type" to AiJson.Arr(listOf(type["type"], aiString("null")))))
    private fun choice(vararg values: String) = aiObject("type" to aiString("string"), "enum" to AiJson.Arr(values.map(::aiString)))
    private fun array(items: AiJson) = aiObject("type" to aiString("array"), "items" to items)
    private fun shape(vararg fields: Pair<String, AiJson>): AiJson.Obj {
        val values = linkedMapOf<String, AiJson>("type" to aiString("object"), "additionalProperties" to aiBool(false))
        if(fields.isNotEmpty()) values["properties"] = AiJson.Obj(fields.associate { it.first.removePrefix("?") to it.second })
        val required = fields.filterNot { it.first.startsWith("?") }.map { aiString(it.first) }
        if(required.isNotEmpty()) values["required"] = AiJson.Arr(required)
        return AiJson.Obj(values)
    }
    private val window = shape("startMinute" to integer, "endMinute" to integer)
    private val windows = array(window)
    private val weekly = aiObject("type" to aiString("object"), "propertyNames" to aiObject("enum" to AiJson.Arr((1..7).map { aiString(it.toString()) })), "additionalProperties" to windows)
    private val slot = choice("ANALYSIS", "TRANSCRIPTION")
    private val connection = shape("?protocol" to choice("OPENAI", "ANTHROPIC"), "?url" to string, "?model" to string)
    private val ai = shape("?analysis" to connection, "?transcription" to connection, "?context" to integer, "?output" to integer)
    private val recording = shape("?recordingEnabled" to boolean, "?automaticAnalysis" to boolean, "?captureMode" to choice("SYSTEM_ONLY", "MICROPHONE_EXPERIMENTAL"))
    private val range = shape("startDate" to string, "endDateInclusive" to string, "?zoneId" to string)
    private val page = shape("?pageIndex" to integer, "?pageSize" to integer)
    private val definitionsMutable = mutableListOf<ToolDefinition>()
    private fun tool(name: String, write: Boolean = false, vararg fields: Pair<String, AiJson>) { definitionsMutable += ToolDefinition(definitionsMutable.size + 1, name, shape(*fields), write) }
    val definitions: List<ToolDefinition>
    val allDefinitions:List<ToolDefinition> get()=definitions+extensions
    private val extensions:List<ToolDefinition> by lazy { listOf(
        ToolDefinition(73,"discover_tools",shape("?category" to string,"?exactNames" to array(string),"?query" to string,"?cursor" to string,"?limit" to integer),false,"按需发现工具及其参数。类别可空浏览全部；exactNames精确名称；limit默认8上限12。零业务读取。"),
        ToolDefinition(74,"data_query",shape("domain" to choice(*AiDomain.entries.map{it.name}.toTypedArray()),"?projection" to string,"?filter" to queryFilter,"?cursor" to string,"?limit" to integer,"?maxBytes" to integer),false,"源级分页查询D01–D21，默认all_stored、limit50/最大200、maxBytes32768/最大131072。必须沿nextCursor遍历，complete=false不代表全集。"),
        ToolDefinition(75,"data_get",shape("domain" to string,"key" to string,"?projection" to string),false,"按分页返回的entityKey读取目标详情；缺失NOT_FOUND；长文用textFields引用。"),
        ToolDefinition(76,"data_aggregate",shape("domain" to string,"?metric" to choice("count","sumDuration","score","reportTotals"),"?filter" to queryFilter,"?groupBy" to choice("number","status","direction","year")),false,"权威匹配全集聚合，不对当前页求和；默认count。业务统计D05，声明时间范围和zone。"),
        ToolDefinition(77,"data_readText",shape("reference" to string,"?cursor" to string,"?maxChars" to integer),false,"读取textFields中的reference全文分块，Unicode码点maxChars默认4096/最大16384；沿游标到complete=true才完整。"),
        ToolDefinition(78,"task_prepare",shape("kind" to choice("CREATE_PROFILES","SET_APPEARANCE","OPERATIONS"),"expectedCount" to integer,"?targets" to array(string)),false,"在多项写前建立完整计划，expectedCount是用户数量，不可降低。返回taskId；CREATE_PROFILES只新建不覆盖，号码由模型生成。"),
        ToolDefinition(79,"callers_saveProfiles",shape("taskId" to string,"items" to array(shape("number" to string,"?name" to string,"?department" to string,"?positionId" to nullable(integer),"?whitelisted" to boolean))),true,"批量添加1–32个档案，须CREATE_PROFILES计划。预检号码合法唯一且不存在，逐项共用saveProfile并读回，部分失败如实返回；姓名使用可识别合成测试标记时不得触碰真实资料。"),
        ToolDefinition(80,"callers_getCallDetail",shape("callId" to integer),false,"按callId读取存储通话详情，包括未计分及隐藏记录；不受UI日期限制。")
    ) }
    private val queryFilter:AiJson.Obj get()=shape("?number" to string,"?id" to integer,"?text" to string,"?start" to integer,"?end" to integer,"?year" to integer,"?status" to string,"?kind" to string,"?date" to string,"?zone" to string)
    init {
        tool("root_checkNumber", false, "raw" to string)
        tool("home_load")
        tool("callers_search", false, "?text" to string, "?page" to page)
        tool("callers_getEditor", false, "?number" to nullable(string))
        tool("callers_getOverview", false, "number" to string, "?workAtEpochMillis" to integer)
        tool("callers_getLatestCall")
        tool("callers_saveProfile", true, "number" to string, "?name" to string, "?department" to string, "?positionId" to nullable(integer), "?whitelisted" to boolean)
        tool("callers_setWhitelisted", true, "number" to string, "enabled" to boolean)
        tool("reports_load", false, "number" to string, "range" to range, "?filter" to choice(*CallFilter.values().map { it.name }.toTypedArray()), "?page" to page)
        tool("reports_exportCsv", true, "number" to string, "range" to range)
        tool("settings_getSummary")
        tool("settings_setStatisticsEnabled", true, "enabled" to boolean)
        tool("settings_setOverlayEnabled", true, "enabled" to boolean)
        tool("settings_getQuietHours")
        tool("settings_saveQuietHours", true, "?mode" to choice("OFF", "NON_WORK", "CUSTOM"), "?allNumbers" to boolean, "?weeklyWindows" to weekly)
        tool("settings_setQuietMode", true, "mode" to choice("OFF", "NON_WORK", "CUSTOM"))
        tool("settings_setQuietEnabled", true, "enabled" to boolean)
        tool("settings_getPositions")
        tool("settings_savePosition", true, "?id" to integer, "?name" to string, "?level" to integer, "?active" to boolean)
        tool("settings_deletePosition", true, "id" to integer)
        tool("settings_setMyPosition", true, "id" to nullable(integer))
        tool("settings_getWorkSchedule")
        tool("settings_saveWorkSchedule", true, "?zoneId" to string, "?weekly" to weekly, "?makeup" to windows)
        tool("settings_resetWorkSchedule", true)
        tool("settings_getCalendar")
        tool("settings_synchronizeCalendar", true, "year" to integer)
        tool("settings_importCalendar", true)
        tool("settings_saveDateOverride", true, "date" to string, "kind" to choice("REST", "WORK"), "?windows" to windows)
        tool("settings_deleteDateOverride", true, "date" to string)
        tool("settings_getLeaves")
        tool("settings_saveLeave", true, "?id" to integer, "?period" to shape("kind" to choice("ALL_DAY", "TIMED"), "?startDate" to string, "?endDateInclusive" to string, "?zoneId" to string, "?startInclusiveEpochMillis" to integer, "?endExclusiveEpochMillis" to integer), "?note" to string)
        tool("settings_deleteLeave", true, "id" to integer)
        tool("settings_getScoreSettings")
        tool("settings_saveScoreSettings", true, "?offHoursWeight" to integer, "?repeatWeight" to integer, "?repeatMinutes" to integer, "?observationDays" to integer)
        tool("settings_resetScoreSettings", true)
        tool("settings_getAppearance")
        tool("settings_saveAppearance", true, "?selection" to choice("PRESET", "CUSTOM"), "?presetId" to string, "?customSeedRgb" to nullable(integer), "?colorMode" to choice("FOLLOW_SYSTEM", "LIGHT", "DARK"))
        tool("ringtone_getSettings")
        tool("ringtone_setEnabled", true, "enabled" to boolean)
        tool("ringtone_importAudio", true)
        tool("ringtone_restoreBuiltIn", true)
        tool("ringtone_startPreview", true)
        tool("ringtone_stopPlayback", true)
        tool("runtime_getStatus")
        tool("runtime_getDiagnostics")
        tool("runtime_requestCallLogSync", true)
        tool("widgets_getBinding", false, "widgetId" to integer)
        tool("widgets_saveBinding", true, "widgetId" to integer, "number" to string)
        tool("widgets_removeBinding", true, "widgetId" to integer)
        tool("recordings_listArchives")
        tool("recordings_getSettings")
        tool("recordings_saveSettings", true, "?recording" to recording, "?ai" to ai)
        tool("recordings_testConnection", true, "slot" to slot)
        tool("recordings_getArchive", false, "callId" to integer)
        tool("recordings_importRecording", true, "callId" to integer, "?handle" to string)
        tool("recordings_retry", true, "callId" to integer)
        tool("recordings_delete", true, "callId" to integer)
        tool("recordings_play", true, "callId" to integer)
        tool("recordings_stop", true)
        tool("recordings_setSystemFolder", true)
        tool("recordings_systemRecordings")
        tool("widgets_listBindings")
        tool("recordings_adoptIdentity", true, "callId" to integer)
        tool("ai_getSettings")
        tool("ai_patchSettings", true, *ai["properties"].let { (it as AiJson.Obj).fields.map { entry -> "?${entry.key}" to entry.value }.toTypedArray() })
        tool("ai_editCredential", true, "slot" to slot)
        tool("system_requestCapability", true, "capability" to choice(*Capability.values().map { it.name }.toTypedArray()))
        tool("system_openApplicationSettings", true)
        tool("system_pinWidget", true)
        tool("system_testOverlay", true)
        tool("system_previewCall", true, "callId" to integer)
        tool("ai_copyConnection", true, "from" to slot, "to" to slot)
        definitions = definitionsMutable.toList()
        check(definitions.size == 72 && definitions.map { it.name }.distinct().size == 72)
    }
    fun validate(call: ConversationToolCall): ToolDefinition {
        require(call.id.isNotBlank() && call.id.length <= 256) { "ai.invalid_tool_response" }
        call.argumentError?.let { throw com.greatwall.callring.application.ports.BackendFailure(it) }
        val definition = allDefinitions.singleOrNull { it.name == call.name } ?: throw IllegalArgumentException("ai.unknown_tool")
        validateValue(call.arguments, definition.schema)
        return definition
    }
    private fun validateValue(value: AiJson, schema: AiJson.Obj) {
        val type = schema["type"]
        val allowed = if(type is AiJson.Arr) type.values.map { it.string() } else listOf(type.string())
        if(value == AiJson.Null) { require("null" in allowed); return }
        when {
            "object" in allowed -> {
                require(value is AiJson.Obj)
                val properties = schema["properties"] as? AiJson.Obj ?: aiObject()
                val names = schema["propertyNames"] as? AiJson.Obj
                val additional = schema["additionalProperties"] as? AiJson.Obj
                require(value.fields.keys.all { it in properties.fields || (names != null && (names["enum"] as AiJson.Arr).values.any { name -> name.string() == it }) }) { "ai.extra_argument" }
                require(((schema["required"] as? AiJson.Arr)?.values ?: emptyList()).all { it.string() in value.fields }) { "ai.missing_argument" }
                value.fields.forEach { (key, item) -> validateValue(item, (properties[key] as? AiJson.Obj ?: additional) ?: throw IllegalArgumentException()) }
            }
            "array" in allowed -> { require(value is AiJson.Arr && value.values.size <= 20000); value.values.forEach { validateValue(it, schema["items"] as AiJson.Obj) } }
            "integer" in allowed -> value.long()
            "boolean" in allowed -> value.boolean()
            "string" in allowed -> { val text = value.string(); val enum = schema["enum"]; require(enum == AiJson.Null || (enum as AiJson.Arr).values.any { it.string() == text }) }
            else -> error("ai.invalid_schema")
        }
    }
}
