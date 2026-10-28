package com.greatwall.callring.entrypoint

import com.greatwall.callring.entrypoint.recording.CaptureServiceController
import com.greatwall.callring.application.recording.RecordingService.CapturePlan
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.domain.calling.RecordingCall
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class CaptureServiceControllerTest {
    private class Rig {
        val events = mutableListOf<String>(); val queue = mutableListOf<Runnable>()
        var failPrepare = false; var failStop = false; var failAttach = false
        var lowSpace=false
        var failureReason: String?=null
        val controller = CaptureServiceController(
            { foreground -> events += "plan:$foreground"; CapturePlan(RecordingCall(1, "100", 1, null), false) },
            { object : CaptureServiceController.Results {
                override fun attach(call: RecordingCall, audio: AudioReference) { events += "attach"; if (failAttach) error("attach") }
                override fun failure(call: RecordingCall, reason: String) { events += "failure"; failureReason=reason }
            } },
            { if(lowSpace) throw java.io.IOException("storage.insufficient_space")
                object : CaptureServiceController.Target {
                override val reference = AudioReference("owned.m4a")
                override fun delete() { events += "delete" }
            } },
            { object : CaptureServiceController.Media {
                override fun configure(plan: CapturePlan, target: CaptureServiceController.Target) { events += "configure" }
                override fun prepare() { events += "prepare"; if (failPrepare) error("prepare") }
                override fun start() { events += "start" }
                override fun stop() { events += "stop"; if (failStop) error("stop") }
                override fun release() { events += "release" }
            } }, { events += "foreground" }, { events += "stopSelf" },
            Executor { queue += it }, { events += "shutdown" })
        fun drain() { while (queue.isNotEmpty()) queue.removeAt(0).run() }
    }
    @Test fun destroyBeforeQueuedStartPreventsPreparationAndQueuesCleanupBeforeShutdown() {
        val rig = Rig(); rig.controller.start(false); rig.controller.destroy(); rig.drain()
        assertEquals(listOf("foreground", "shutdown"), rig.events)
    }
    @Test fun lowSpaceRefusesCaptureBeforeMediaAndReportsSpecificReason() {
        val rig=Rig(); rig.lowSpace=true; rig.controller.start(false); rig.drain()
        assertEquals("recording.insufficient_space",rig.failureReason)
        assertFalse("prepare" in rig.events)
        assertFalse("start" in rig.events)
    }
    @Test fun successfulStopReleasesBeforeAttachingAndDuplicateStartDoesNotForegroundAgain() {
        val rig = Rig(); rig.controller.start(false); rig.drain(); rig.controller.start(false)
        rig.controller.destroy(); rig.drain()
        assertEquals(listOf("foreground", "plan:false", "configure", "prepare", "start", "shutdown", "stop", "release", "attach"), rig.events)
    }
    @Test fun prepareFailureReleasesDeletesReportsThenStops() {
        val rig = Rig(); rig.failPrepare = true; rig.controller.start(false); rig.drain()
        assertEquals(listOf("foreground", "plan:false", "configure", "prepare", "release", "delete", "failure", "stopSelf"), rig.events)
    }
    @Test fun stopFailureDeletesWithoutAttaching() {
        val rig = Rig(); rig.failStop = true; rig.controller.start(false); rig.drain(); rig.controller.destroy(); rig.drain()
        assertEquals(listOf("stop", "release", "delete", "failure"), rig.events.takeLast(4))
    }
    @Test fun attachmentFailureDeletesAndReportsWithoutSecondStop() {
        val rig = Rig(); rig.failAttach = true; rig.controller.start(false); rig.drain(); rig.controller.destroy(); rig.drain()
        assertEquals(listOf("stop", "release", "attach", "delete", "failure"), rig.events.takeLast(5))
    }
    @Test fun inactivePhoneStateNeverResolvesPlanAndForegroundPlanIsRecomputedAtStart() {
        val rig = Rig()
        CaptureServiceController.phoneState(false, { error("must stay lazy") }, {}, { rig.events += "stopService" }, {})
        CaptureServiceController.phoneState(true, {
            rig.events += "plan:true"; CapturePlan(RecordingCall(2, "200", 2, null), false)
        }, { rig.controller.start(false) }, {}, {})
        rig.drain()
        assertEquals(listOf("stopService", "plan:true", "foreground", "plan:false", "configure", "prepare", "start"), rig.events)
    }

}
