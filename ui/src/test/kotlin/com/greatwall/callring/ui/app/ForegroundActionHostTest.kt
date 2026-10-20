package com.greatwall.callring.ui.app

import android.os.Looper
import androidx.activity.ComponentActivity
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundActionHostTest {
    @Test fun rotationAttachesNewHostWithoutRelaunchingClaimedTicket() = runBlocking {
        val foreground = UiForegroundFixture()
        var launches = 0
        val bridge = object : ForegroundActionApi by foreground {
            override fun beginLaunch(requestId: String, token: String) = foreground.beginLaunch(requestId,token).also { if(it) launches++ }
        }
        val api = object : UiFakeApi() { override val conversation = object : ConversationApi by UnavailableConversationApi { override val foreground = bridge } }
        val result = async { foreground.request() }
        yield()
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        ForegroundActionHost(first.get(),api,{})
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,launches)
        val ticket = foreground.pending.value!!
        val saved = android.os.Bundle(); first.saveInstanceState(saved).pause().stop().destroy()
        val second = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume()
        ForegroundActionHost(second.get(),api,{})
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,launches)
        assertTrue(foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.CANCELLED)))
        assertEquals(ForegroundResultStatus.CANCELLED,result.await().status)
        assertFalse(foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        second.pause().stop().destroy()
        Unit
    }
}
