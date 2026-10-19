@file:Suppress("DEPRECATION")
package com.greatwall.callring

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.ForegroundActionService
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.bootstrap.BackendGraph
import com.greatwall.callring.platform.ai.parseConversationJson
import com.greatwall.callring.ui.app.CallringActivity
import com.greatwall.callring.ui.app.UiDestination
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Explicit opt-in only. Never selected by the isolated backend QA runner. */
class AiConversationPreflightDeviceTest:InstrumentationTestCase() {
    fun testSafeFormalConfiguration()=runBlocking {
        val context=instrumentation.targetContext.applicationContext
        check(context.packageName=="com.greatwall.callring")
        val graph=(context as CallringApplication).backendGraph
        var view=graph.api.aiSettings.getAiView().requiredValue()
        val originalCompatibility=view.compatibility
        if((instrumentation as InstrumentationTestRunner).arguments.getString("ai_v2_enable_fast")=="true") {
            check(view.analysis.protocol==AiProtocol.OPENAI&&view.analysis.model=="qwen3.8-flash"&&view.analysis.keyConfigured)
            view=graph.api.aiSettings.patchAi(view,AiSettingsPatch(compatibility=PatchField.Set(ConversationCompatibility.QWEN_FAST))).requiredValue()
            check(view.analysis.resolvedProfile==ProviderProfile.QWEN_FAST)
        }
        val report=JSONObject().put("protocol",view.analysis.protocol.name).put("model",view.analysis.model)
            .put("keyConfigured",view.analysis.keyConfigured).put("resolvedProfile",view.analysis.resolvedProfile.name)
            .put("compatibility",view.compatibility.name).put("contextBudget",view.budget.context).put("outputBudget",view.budget.output)
            .put("originalCompatibility",originalCompatibility.name)
            .put("batchStarted",context.getSharedPreferences(PROOF_PREFS,Context.MODE_PRIVATE).getBoolean("batchStarted",false))
        File(proofDirectory(context),"preflight.json").writeText(report.toString(2))
        instrumentation.sendStatus(2,Bundle().apply{putString("stream","AI_V2 preflight:$report\n")})
    }
}

class RealAiConversationDeviceTest:InstrumentationTestCase() {
    private var shownActivity:android.app.Activity?=null
    fun testAuthorizedFormalActivityAndRealModel()=runBlocking {
        val context=instrumentation.targetContext.applicationContext
        check(context.packageName=="com.greatwall.callring"){"Formal package required"}
        check((instrumentation as InstrumentationTestRunner).arguments.getString("ai_v2_allow_paid")=="true"){"Explicit paid-model test argument required"}
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked){"Unlock device before acceptance"}
        val journal=context.getSharedPreferences(PROOF_PREFS,Context.MODE_PRIVATE)
        check(!journal.getBoolean("batchStarted",false)){"Number batch already attempted; inspect proof and run persistence verification only"}
        val graph=(context as CallringApplication).backendGraph
        val api=graph.api
        check(api.conversation.state.value.messages.isEmpty()){"A fresh process is required; existing chat will not be discarded"}
        val view=api.aiSettings.getAiView().requiredValue()
        check(view.analysis.protocol==AiProtocol.OPENAI&&view.analysis.model=="qwen3.8-flash"&&view.analysis.resolvedProfile==ProviderProfile.QWEN_FAST&&view.analysis.keyConfigured){"Configured Qwen FAST analysis connection required"}
        val report=JSONObject().put("package",context.packageName).put("model",view.analysis.model).put("protocol",view.analysis.protocol.name).put("resolvedProfile",view.analysis.resolvedProfile.name).put("startedAt",System.currentTimeMillis()).put("realClient",true)
        val activity=instrumentation.startActivitySync(CallringActivity.intent(context,UiDestination.Conversation).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK}).also{shownActivity=it}
        instrumentation.runOnMainSync{activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val files=proofDirectory(context)
        try {
            instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            instrumentation.waitForIdleSync()
            check((api.conversation.foreground as ForegroundActionService).hasHost){"Real resumed Activity host required"}
            awaitQuiet(graph)
            val beforeIdle=graph.businessReadCounters()
            val idle=sendThroughActivity(graph,"你好","idle-keyboard.png")
            val afterIdle=graph.businessReadCounters()
            report.put("idle",roundJson("你好",idle)).put("idleCounterDelta",JSONObject(beforeIdle.mapValues{(key,value)->afterIdle.getValue(key)-value}))
            check(idle.phase==ConversationPhase.COMPLETED&&idle.exchanges.size==1&&idle.sourceQueries==0L){"Ordinary chat must use one request and zero source queries"}
            check(beforeIdle==afterIdle){"Ordinary chat unexpectedly read business/runtime/classifier sources"}
            val originalAppearance=api.settings.getAppearance().requiredValue()
            api.edits.appearance(originalAppearance,originalAppearance.copy(colorMode=ColorMode.LIGHT)).requiredValue()
            check(api.settings.getAppearance().requiredValue().colorMode==ColorMode.LIGHT)
            settle()
            screenshot(File(files,"light-before.png"))
            val before=profiles(graph)
            val created=sendThroughActivity(graph,"添加 10 个随机号码档案","create-keyboard.png",beforeSend={check(journal.edit().putBoolean("batchStarted",true).commit())})
            report.put("creation",roundJson("添加 10 个随机号码档案",created))
            val after=profiles(graph)
            val additions=after.filterKeys{it !in before}
            val preserved=before.all{(number,value)->after[number]==value}
            check(journal.edit().putString("createdProfiles",JSONArray(additions.values.map{JSONObject(it.encode())}).toString()).putInt("createdCount",additions.size).commit())
            report.put("newProfiles",JSONArray(additions.values.map{JSONObject(it.encode())})).put("existingProfilesUnchanged",preserved).put("newCount",additions.size)
            writeReport(files,report)
            check(preserved&&after.size==before.size+10&&additions.size==10){"Expected exactly ten new profiles and unchanged existing profiles"}
            check(additions.keys.all{api.checkNumber(it).validProfileNumber})
            check(additions.values.all{(it["name"] as? AiJson.Str)?.value?.startsWith("合成测试")==true}){"Generated names must be recognizable synthetic profiles"}
            check(created.phase==ConversationPhase.COMPLETED){"Creation did not complete with authoritative verification"}
            check(created.receipts.distinctBy{it.operationId}.count{it.status==ToolStatus.COMMITTED&&it.verification==VerificationStatus.VERIFIED}==10){"Ten verified local write receipts required"}
            screenshot(File(files,"ten-created.png"))
            val theme=sendThroughActivity(graph,"修改深色模式","theme-keyboard.png")
            report.put("theme",roundJson("修改深色模式",theme))
            val actual=api.settings.getAppearance().requiredValue()
            report.put("themeAfter",actual.colorMode.name)
            check(theme.phase==ConversationPhase.COMPLETED&&actual.colorMode==ColorMode.DARK){"Real model must persist DARK from LIGHT"}
            check(theme.receipts.any{it.status==ToolStatus.COMMITTED&&it.verification==VerificationStatus.VERIFIED})
            settle();screenshot(File(files,"dark-after.png"))
            check(journal.edit().putBoolean("batchComplete",true).putBoolean("themeComplete",true).commit())
            report.put("result","passed").put("endedAt",System.currentTimeMillis())
            writeReport(files,report)
            status("formal-model-and-activity-passed")
        } catch(e:Throwable) {
            report.put("result","failed").put("failureType",e.javaClass.simpleName).put("endedAt",System.currentTimeMillis())
            report.put("lastPhase",api.conversation.state.value.phase.name)
            writeReport(files,report)
            throw e
        } finally {instrumentation.runOnMainSync{activity.finish()}}
    }
    private suspend fun sendThroughActivity(graph:BackendGraph,prompt:String,keyboardFile:String,beforeSend:()->Unit={}):ConversationState {
        status("typing:$prompt")
        val before=graph.api.conversation.state.value.messages.size
        val editor=waitNode{it.isEditable&&it.isEnabled&&it.isVisibleToUser}
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,prompt)})){"Composer input failed"}
        editor.recycle()
        settle()
        screenshot(File(proofDirectory(instrumentation.targetContext),keyboardFile))
        val send=waitNode{it.contentDescription?.toString()=="发送消息"&&it.isEnabled&&it.isVisibleToUser}
        beforeSend()
        check(send.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Visible send action failed"};send.recycle()
        val state=withTimeout(300000){graph.api.conversation.state.first{s->!s.busy&&s.messages.drop(before).any{it.role=="user"&&it.text==prompt}}}
        check(state.exchanges.isNotEmpty()){"Actual network exchange metrics required"}
        check(state.exchanges.all{it.profile==ProviderProfile.QWEN_FAST&&it.requestFlags==mapOf("enable_thinking" to false,"preserve_thinking" to false)}){"FAST flags missing on an actual request"}
        var ime=false
        instrumentation.runOnMainSync{
            val decor=shownActivity?.window?.decorView
            ime=if(Build.VERSION.SDK_INT>=30)decor?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true else decor?.let{view->val visible=android.graphics.Rect();view.getWindowVisibleDisplayFrame(visible);view.height-visible.height()>view.resources.displayMetrics.density*150}?:false
        }
        // Hide via the real keyboard only when the active window reports an input method.
        if(ime)instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        settle()
        status("round:${state.phase.name}:requests=${state.exchanges.size}:queries=${state.sourceQueries}")
        return state
    }
    private fun waitNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        val deadline=SystemClock.elapsedRealtime()+10000
        while(SystemClock.elapsedRealtime()<deadline){findNode(predicate)?.let{return it};SystemClock.sleep(100)}
        error("Required visible Activity control was not found")
    }
    private fun findNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        val roots=instrumentation.uiAutomation.windows.mapNotNull{it.root}.filter{node->
            if(node.packageName?.toString()==instrumentation.targetContext.packageName)true else{node.recycle();false}
        }
        val queue=ArrayDeque<AccessibilityNodeInfo>();queue.addAll(roots)
        var visited=0
        while(queue.isNotEmpty()&&visited++<5000){val node=queue.removeFirst();if(predicate(node)){queue.forEach{it.recycle()};return node};repeat(node.childCount){node.getChild(it)?.let{child->queue+=child}};node.recycle()}
        queue.forEach{it.recycle()};return null
    }
    private fun settle(){instrumentation.waitForIdleSync();SystemClock.sleep(500);instrumentation.waitForIdleSync()}
    private fun awaitQuiet(graph:BackendGraph) {
        var previous=graph.businessReadCounters();var stable=SystemClock.elapsedRealtime();val deadline=stable+15000
        while(SystemClock.elapsedRealtime()<deadline){SystemClock.sleep(100);val current=graph.businessReadCounters();if(current!=previous){previous=current;stable=SystemClock.elapsedRealtime()};if(SystemClock.elapsedRealtime()-stable>=1500)return}
        error("Background business reads did not settle before measurement")
    }
    private fun screenshot(file:File){val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("Real device screenshot unavailable");file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()}
    private fun status(value:String){instrumentation.sendStatus(2,Bundle().apply{putString("stream","AI_V2 $value\n")})}
}

/** Run after an explicit force-stop/relaunch; it performs no paid calls or business writes. */
class AiConversationPersistenceDeviceTest:InstrumentationTestCase() {
    fun testFormalProfilesAndDarkThemeSurviveProcessRestart()=runBlocking {
        val context=instrumentation.targetContext.applicationContext
        check(context.packageName=="com.greatwall.callring")
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked){"Unlock device before persistence verification"}
        val journal=context.getSharedPreferences(PROOF_PREFS,Context.MODE_PRIVATE)
        check(journal.getBoolean("batchComplete",false)&&journal.getBoolean("themeComplete",false)){"A completed formal run is required"}
        val expected=JSONArray(journal.getString("createdProfiles",null)?:error("Created profile proof missing"))
        check(expected.length()==10)
        val graph=(context as CallringApplication).backendGraph
        val actual=profiles(graph)
        repeat(expected.length()){i->val row=expected.getJSONObject(i);check(actual[row.getString("number")]==parseConversationJson(row.toString())){"A persisted synthetic profile changed"}}
        check(graph.api.settings.getAppearance().requiredValue().colorMode==ColorMode.DARK)
        check(graph.api.conversation.state.value.messages.isEmpty()){"Chat must not persist across process restart"}
        val activity=instrumentation.startActivitySync(CallringActivity.intent(context,UiDestination.Conversation).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK})
        try {
            instrumentation.waitForIdleSync();SystemClock.sleep(750)
            val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("Restart screenshot unavailable")
            File(proofDirectory(context),"restart-dark.png").outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        }finally{instrumentation.runOnMainSync{activity.finish()}}
        val file=File(proofDirectory(context),"persistence.json")
        file.writeText(JSONObject().put("result","passed").put("profiles",10).put("theme","DARK").put("chatPersisted",false).put("verifiedAt",System.currentTimeMillis()).toString(2))
    }
}
private const val PROOF_PREFS="ai_rectification_v2_proof_20261019"
private fun proofDirectory(context:Context)=File(context.getExternalFilesDir(null)?:context.filesDir,"ai-v2-proof").apply{check(isDirectory||mkdirs())}
private fun writeReport(directory:File,report:JSONObject){File(directory,"real-model.json").writeText(report.toString(2))}
private suspend fun profiles(graph:BackendGraph):Map<String,AiJson.Obj> {
    val rows=linkedMapOf<String,AiJson.Obj>();var cursor:String?=null
    do {val page=graph.queries.query(QuerySpec(AiDomain.D01,cursor=cursor,limit=200)).requiredValue();check(page.status in setOf(QueryStatus.COMPLETE,QueryStatus.EMPTY,QueryStatus.PARTIAL));page.items.filter{it["stored"]==aiNumber(1)}.forEach{row->rows[row["number"].string()]=row};check(rows.size<=4000){"Acceptance inventory exceeds bounded local capacity"};cursor=page.nextCursor;if(cursor==null)check(page.complete)}while(cursor!=null)
    return rows
}
private fun roundJson(prompt:String,state:ConversationState):JSONObject = JSONObject().put("prompt",prompt).put("phase",state.phase.name).put("modelRequests",state.exchanges.size).put("sourceQueries",state.sourceQueries).put("work",state.workCount).put("outcome",JSONObject(state.outcome.encode())).put("error",state.error?.message?:JSONObject.NULL).put("errorKind",state.error?.aiFailure?.kind?.name?:JSONObject.NULL).put("exchanges",JSONArray(state.exchanges.map{m->JSONObject().put("requestBytes",m.requestBytes).put("responseBytes",m.responseBytes).put("httpStatus",m.httpStatus?:JSONObject.NULL).put("profile",m.profile.name).put("elapsedMillis",m.elapsedMillis).put("requestFlags",JSONObject(m.requestFlags)).put("finishReason",m.diagnostics?.finishReason?:JSONObject.NULL).put("usage",m.diagnostics?.usage?.let{u->JSONObject().put("input",u.input?:JSONObject.NULL).put("output",u.output?:JSONObject.NULL).put("total",u.total?:JSONObject.NULL).put("cachedInput",u.cachedInput?:JSONObject.NULL).put("cacheWriteInput",u.cacheWriteInput?:JSONObject.NULL).put("reasoningOutput",u.reasoningOutput?:JSONObject.NULL)}?:JSONObject.NULL).put("failure",m.failure?.name?:JSONObject.NULL)}))
