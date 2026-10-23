package com.greatwall.callring

import com.greatwall.callring.domain.calling.DecisionSnapshot

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.calling.DecisionQueryService
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DecisionPublicationTest {
    @Test
    fun coldCaptureAndPatchesPublishInOneFenceGeneration() {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val patchStarted = CountDownLatch(1)
        val coordinator = BusinessRevisionCoordinator()
        val snapshots = AtomicReference<DecisionSnapshot?>()
        val source =
            DecisionFixture()
                .decisionInputs()
                .copy(quiet = QuietSettings(QuietMode.NON_WORK, false, emptyMap()))
        val inputs =
            object : DecisionInputsTransactions {
                override fun <T> readDecisionInputs(block: (DecisionInputsReader) -> T): T =
                    block(
                        object : DecisionInputsReader {
                            override fun decisionInputs(): DecisionInputs {
                                captured.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                return source
                            }
                        },
                    )
            }
        val calls =
            object : CallingTransactions {
                override fun <T> readCalling(block: (CallingReader) -> T): T =
                    error("no call access during preparation")

                override fun <T> writeCalling(block: (CallingWriter) -> T): T =
                    error("no call access during preparation")
            }
        val service = DecisionQueryService(inputs, calls, coordinator, snapshots, com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cold = executor.submit<DecisionSnapshot> { service.refresh() }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val patch = executor.submit {
                patchStarted.countDown()
                coordinator.guard(null) {
                    val latest = snapshots.get()!!
                    snapshots.set(
                        DecisionSnapshot.capture(
                            latest.inputs.copy(
                                quiet = QuietSettings(QuietMode.OFF, false, emptyMap()),
                                whitelistedNumbers = latest.inputs.whitelistedNumbers + "31234",
                            ),
                            com.greatwall.callring.domain.calling.PrefixLengthNumberMatcher,
                        ),
                    )
                }
            }
            assertTrue(patchStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            cold.get(5, TimeUnit.SECONDS)
            patch.get(5, TimeUnit.SECONDS)
            assertEquals(QuietMode.OFF, service.snapshot().inputs.quiet.mode)
            assertEquals(setOf("31234"), service.snapshot().inputs.whitelistedNumbers)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
    @Test fun cachedSnapshotReadWaitsUntilCommitFencePublicationFinishes() {
        val coordinator=BusinessRevisionCoordinator()
        val matcher=com.greatwall.callring.domain.calling.NumberMatcher { _,_ -> false }
        val inputs=object : DecisionInputsTransactions {
            override fun <T> readDecisionInputs(block: (DecisionInputsReader)->T):T=error("cached read must not reload")
        }
        val calls=object : CallingTransactions {
            override fun <T> readCalling(block:(CallingReader)->T):T=error("no calls")
            override fun <T> writeCalling(block:(CallingWriter)->T):T=error("no calls")
        }
        val old=DecisionSnapshot.capture(DecisionFixture().decisionInputs(),matcher)
        val snapshots=AtomicReference<DecisionSnapshot?>(old)
        val service=DecisionQueryService(inputs,calls,coordinator,snapshots,matcher)
        val published=CountDownLatch(1)
        val release=CountDownLatch(1)
        val reading=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val committing=executor.submit { coordinator.guard {
                snapshots.set(old.rebuild(old.inputs.copy(numberRule=com.greatwall.callring.domain.calling.NumberRuleSnapshot(
                    com.greatwall.callring.domain.calling.NumberRule("8",6),1))))
                published.countDown()
                check(release.await(5,TimeUnit.SECONDS))
            } }
            assertTrue(published.await(3,TimeUnit.SECONDS))
            val cached=executor.submit<DecisionSnapshot> { reading.countDown();service.snapshot() }
            assertTrue(reading.await(3,TimeUnit.SECONDS))
            try { cached.get(150,TimeUnit.MILLISECONDS);fail("cached snapshot bypassed afterCommit fence") }
            catch (_:TimeoutException) { }
            release.countDown()
            committing.get(3,TimeUnit.SECONDS)
            val result=cached.get(3,TimeUnit.SECONDS)
            assertEquals(1L,result.inputs.numberRule.revision)
            assertFalse(result.evaluate("812345",1000).matched)
        } finally { release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)) }
    }

}
