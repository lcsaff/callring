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
        val report=JSONObject().put("schemaVersion",2).put("package",context.packageName).put("model",view.analysis.model).put("protocol",view.analysis.protocol.name).put("resolvedProfile",view.analysis.resolvedProfile.name).put("startedAt",System.currentTimeMillis()).put("realClient",true)
        val activity=launchProofActivity(instrumentation,UiDestination.Conversation,"conversation").also{shownActivity=it}
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
            report.put("existingProfilesUnchanged",preserved).put("newCount",additions.size)
            writeReport(files,report)
            check(preserved&&after.size==before.size+10&&additions.size==10){"Expected exactly ten new profiles and unchanged existing profiles"}
            check(additions.keys.all{api.checkNumber(it).validProfileNumber})
            check(additions.values.all{(it["name"] as? AiJson.Str)?.value?.startsWith("合成测试")==true}){"Generated names must be recognizable synthetic profiles"}
            check(created.phase==ConversationPhase.COMPLETED){"Creation did not complete with authoritative verification"}
            check(created.receipts.distinctBy{it.operationId}.count{it.status==ToolStatus.COMMITTED&&it.verification==VerificationStatus.VERIFIED}==10){"Ten verified local write receipts required"}
            report.put("profileChecks",JSONArray(additions.values.mapIndexed{i,row->JSONObject().put("step",i+1).put("legal",api.checkNumber(row["number"].string()).validProfileNumber).put("syntheticName",row["name"].string().startsWith("合成测试")).put("uniqueNewTarget",true).put("verifiedFields",true)}))
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
        val bounds=android.graphics.Rect();send.getBoundsInScreen(bounds);send.recycle()
        check(!bounds.isEmpty){"Visible send bounds required"}
        val touchTime=SystemClock.uptimeMillis()
        for(action in intArrayOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(touchTime,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try{check(instrumentation.uiAutomation.injectInputEvent(event,true)){"Visible send touch failed"}}finally{event.recycle()}
        }
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
        val activity=launchProofActivity(instrumentation,UiDestination.Conversation,"conversation")
        try {
            instrumentation.waitForIdleSync();SystemClock.sleep(750)
            val bitmap=proofWindowBitmap(activity)
            File(proofDirectory(context),"restart-dark.png").outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        }finally{instrumentation.runOnMainSync{activity.finish()}}
        val file=File(proofDirectory(context),"persistence.json")
        file.writeText(JSONObject().put("result","passed").put("profiles",10).put("theme","DARK").put("chatPersisted",false).put("verifiedAt",System.currentTimeMillis()).toString(2))
    }
}

/** Read-only UI checks for this run's synthetic journal; never selects or saves an old profile. */
class AiConversationSyntheticUiDeviceTest:InstrumentationTestCase() {
    fun testSyntheticProfileListAndEditorReflectPersistedWrites()=runBlocking {
        val context=instrumentation.targetContext.applicationContext
        check(context.packageName=="com.greatwall.callring")
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val journal=context.getSharedPreferences(PROOF_PREFS,Context.MODE_PRIVATE)
        check(journal.getBoolean("batchComplete",false))
        val row=JSONArray(journal.getString("createdProfiles",null)?:error("Journal required")).getJSONObject(0)
        val number=row.getString("number");val name=row.getString("name")
        check(number.matches(Regex("\\+?[0-9]+"))&&name.startsWith("合成测试"))
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun find(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            val nodes=ArrayDeque<AccessibilityNodeInfo>();nodes.addAll(instrumentation.uiAutomation.windows.mapNotNull{it.root})
            while(nodes.isNotEmpty()){val node=nodes.removeFirst();if(node.packageName?.toString()==context.packageName&&predicate(node)){nodes.forEach{it.recycle()};return node};repeat(node.childCount){node.getChild(it)?.let(nodes::addLast)};node.recycle()};return null
        }
        fun waitNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
            val deadline=SystemClock.elapsedRealtime()+15000
            while(SystemClock.elapsedRealtime()<deadline){find(predicate)?.let{return it};SystemClock.sleep(100)}
            error("Synthetic UI value missing")
        }
        fun capture(activity:android.app.Activity,name:String){instrumentation.waitForIdleSync();val bitmap=proofWindowBitmap(activity);File(proofDirectory(context),name).outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()}
        fun stage(value:String){File(proofDirectory(context),"synthetic-ui-stage.txt").appendText(value+"\n")}
        stage("launch-list")
        val list=launchProofActivity(instrumentation,UiDestination.Callers,"callers")
        try{
            stage("find-search");val search=waitNode{it.isEditable&&it.isEnabled};check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)}));search.recycle();stage("search-set")
            waitNode{!it.isEditable&&it.text?.toString()?.contains(name)==true}.recycle()
            stage("matched");SystemClock.sleep(750);capture(list,"synthetic-list.png")
        }finally{instrumentation.runOnMainSync{list.finish()}}
        stage("launch-editor");val editor=launchProofActivity(instrumentation,UiDestination.EditCaller(number),"edit",number)
        try{
            waitNode{it.text?.toString()==name}.recycle();waitNode{it.text?.toString()==number}.recycle()
            SystemClock.sleep(750);capture(editor,"synthetic-editor.png")
        }finally{instrumentation.runOnMainSync{editor.finish()}}
        stage("editor-checked");check((context as CallringApplication).backendGraph.api.settings.getAppearance().requiredValue().colorMode==ColorMode.DARK)
        File(proofDirectory(context),"synthetic-ui.json").writeText(JSONObject().put("schemaVersion",2).put("result","passed").put("filteredList",true).put("editorNumberAndName",true).put("businessWrites",0).toString(2))
    }
}

/** Read-only presentation proof, no messages sent and no business values edited. */
class AiConversationPresentationDeviceTest:InstrumentationTestCase() {
    fun testCompactDisclosureAndDetailsUseActualConfiguredTarget()=runBlocking<Unit> {
        val context=instrumentation.targetContext.applicationContext
        check(context.packageName=="com.greatwall.callring"&&!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val graph=(context as CallringApplication).backendGraph
        val view=graph.api.aiSettings.getAiView().requiredValue().analysis
        check(graph.api.conversation.state.value.messages.isEmpty())
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            val nodes=ArrayDeque<AccessibilityNodeInfo>();nodes.addAll(instrumentation.uiAutomation.windows.mapNotNull{it.root})
            while(nodes.isNotEmpty()){val n=nodes.removeFirst();if(n.packageName?.toString()==context.packageName&&predicate(n)){nodes.forEach{it.recycle()};return n};repeat(n.childCount){n.getChild(it)?.let(nodes::addLast)};n.recycle()};return null
        }
        fun waitNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(SystemClock.elapsedRealtime()<deadline){node(predicate)?.let{return it};SystemClock.sleep(100)};error("Presentation control missing")
        }
        fun touch(n:AccessibilityNodeInfo){val bounds=android.graphics.Rect();n.getBoundsInScreen(bounds);n.recycle();check(!bounds.isEmpty);val time=SystemClock.uptimeMillis()
            for(action in intArrayOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){val event=android.view.MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(instrumentation.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}}
        }
        val activity=launchProofActivity(instrumentation,UiDestination.Conversation,"conversation")
        fun capture(name:String){instrumentation.waitForIdleSync();SystemClock.sleep(400);val bitmap=proofWindowBitmap(activity);File(proofDirectory(context),name).outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()}
        try{
            val summary=waitNode{it.text?.toString()?.startsWith("服务：")==true}
            check(summary.text.toString().contains(view.model)&&!summary.text.toString().contains("://"));summary.recycle()
            capture("compact-default.png")
            touch(waitNode{it.contentDescription?.toString()=="服务详情"})
            val full="${view.protocol} · ${view.url} · ${view.model} · ${view.resolvedProfile}"
            waitNode{it.text?.toString()==full}.recycle();capture("connection-details.png")
            touch(waitNode{it.text?.toString()=="关闭"})
            waitNode{it.text?.toString()?.startsWith("服务：")==true}.recycle()
            touch(waitNode{it.isEditable&&it.isEnabled})
            if(android.os.Build.VERSION.SDK_INT>=30){
            val imeDeadline=SystemClock.elapsedRealtime()+10000
            while(SystemClock.elapsedRealtime()<imeDeadline&&activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true)SystemClock.sleep(100)
            check(activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true){"Actual keyboard not visible"}
            }else error("Presentation IME proof requires API 30 or later")
            capture("compact-keyboard.png")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            check(graph.api.conversation.state.value.exchanges.isEmpty()&&graph.api.conversation.state.value.receipts.isEmpty())
            check(graph.api.settings.getAppearance().requiredValue().colorMode==ColorMode.DARK)
            File(proofDirectory(context),"presentation.json").writeText(JSONObject().put("schemaVersion",2).put("result","passed").put("compactTarget",true).put("fullDetailsMatchSafeView",true).put("actualImeVisible",true).put("modelRequests",0).put("businessWrites",0).toString(2))
        }finally{instrumentation.runOnMainSync{activity.finish()}}
    }
}

private fun launchProofActivity(instrumentation:android.app.Instrumentation,destination:UiDestination,target:String,number:String?=null):android.app.Activity {
    val suffix=number?.let{check(it.matches(Regex("\\+?[0-9]+")));" --es callring.ui.number $it"}.orEmpty()
    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start --display 0 -n com.greatwall.callring/com.greatwall.callring.ui.app.CallringActivity --es callring.ui.target $target$suffix")).use{it.readBytes()}
    return instrumentation.startActivitySync(CallringActivity.intent(instrumentation.targetContext,destination).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK}).also{activity->instrumentation.runOnMainSync{activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
}
private fun proofWindowBitmap(activity:android.app.Activity):Bitmap {
    val bitmap=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888)
    val finished=java.util.concurrent.CountDownLatch(1);var result=-1
    android.view.PixelCopy.request(activity.window,bitmap,{value->result=value;finished.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
    check(finished.await(5,java.util.concurrent.TimeUnit.SECONDS)&&result==android.view.PixelCopy.SUCCESS){"Actual Activity surface copy failed"}
    return bitmap
}
private const val PROOF_PREFS="ai_rectification_v2_proof_20261019"
private fun proofDirectory(context:Context)=File(context.getExternalFilesDir(null)?:context.filesDir,"ai-v2-proof").apply{check(isDirectory||mkdirs())}
private fun writeReport(directory:File,report:JSONObject){File(directory,"real-model.json").writeText(report.toString(2))}
private suspend fun profiles(graph:BackendGraph):Map<String,AiJson.Obj> {
    val rows=linkedMapOf<String,AiJson.Obj>();var cursor:String?=null
    do {val page=graph.queries.query(QuerySpec(AiDomain.D01,cursor=cursor,limit=200)).requiredValue();check(page.status in setOf(QueryStatus.COMPLETE,QueryStatus.EMPTY,QueryStatus.PARTIAL));page.items.filter{it["stored"]==aiNumber(1)}.forEach{row->rows[row["number"].string()]=row};check(rows.size<=4000){"Acceptance inventory exceeds bounded local capacity"};cursor=page.nextCursor;if(cursor==null)check(page.complete)}while(cursor!=null)
    return rows
}
private fun roundJson(prompt:String,state:ConversationState):JSONObject = JSONObject().put("schemaVersion",2).put("diagnosticsComplete",state.diagnosticsComplete).put("phaseSpans",JSONArray(state.phaseSpans.map{p->JSONObject().put("phase",p.phase.name).put("startMillis",p.startMillis).put("endMillis",p.endMillis)})).put("receipts",JSONArray(state.receipts.map{r->JSONObject().put("stepId",r.operationId).put("status",r.status.name).put("verification",r.verification.name).put("noOp",r.noOp)})).put("prompt",prompt).put("phase",state.phase.name).put("modelRequests",state.exchanges.size).put("sourceQueries",state.sourceQueries).put("work",state.workCount).put("outcome",JSONObject(diagnosticOutcome(state.outcome).encode())).put("error",state.error?.message?:JSONObject.NULL).put("errorKind",state.error?.aiFailure?.kind?.name?:JSONObject.NULL).put("exchanges",JSONArray(state.exchanges.map{m->JSONObject().put("schemaCount",m.schemaCount?:JSONObject.NULL).put("contextMessages",m.contextMessages?:JSONObject.NULL).put("requestBytes",m.requestBytes).put("responseBytes",m.responseBytes).put("httpStatus",m.httpStatus?:JSONObject.NULL).put("profile",m.profile.name).put("elapsedMillis",m.elapsedMillis).put("requestFlags",JSONObject(m.requestFlags)).put("finishReason",m.diagnostics?.finishReason?:JSONObject.NULL).put("usage",m.diagnostics?.usage?.let{u->JSONObject().put("input",u.input?:JSONObject.NULL).put("output",u.output?:JSONObject.NULL).put("total",u.total?:JSONObject.NULL).put("cachedInput",u.cachedInput?:JSONObject.NULL).put("cacheWriteInput",u.cacheWriteInput?:JSONObject.NULL).put("reasoningOutput",u.reasoningOutput?:JSONObject.NULL)}?:JSONObject.NULL).put("failure",m.failure?.name?:JSONObject.NULL)}))

private fun diagnosticOutcome(value:AiJson):AiJson.Obj {
    val root=value as? AiJson.Obj?:return aiObject()
    val counts=root.fields.filterKeys{it in setOf("verifiedWrites","committedUnverified","unknown","complete","work")}
    val plans=(root["plans"] as? AiJson.Arr)?.values.orEmpty().filterIsInstance<AiJson.Obj>().map{p->AiJson.Obj(p.fields.filterKeys{it in setOf("taskId","kind","expectedCount","verifiedCount","satisfied")})}
    return AiJson.Obj(counts+mapOf("plans" to AiJson.Arr(plans)))
}
