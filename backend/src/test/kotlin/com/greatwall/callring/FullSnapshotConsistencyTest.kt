package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FullSnapshotConsistencyTest {
    @Test
    fun wholeSnapshotFailureReleasesOnlyItsUnpublishedResourceHandles() = runBlocking {
        for (mode in listOf("changed", "failed", "budget", "success")) {
            val resources = ForegroundResourceRegistry()
            val existing =
                com.greatwall.callring.contracts.recording.RecordingFile(
                    "content://synthetic/old",
                    "old.wav",
                    1,
                    1,
                )
            val delivered = resources.issue(existing)
            val coordinator = BusinessRevisionCoordinator()
            var attempts = 0
            val pending = mutableListOf<String>()
            val sources =
                mutableListOf<suspend (Long) -> Map<String, SnapshotPart>>(
                    { _ ->
                        attempts++
                        val part =
                            resources.snapshotPart(
                                listOf(
                                    existing,
                                    com.greatwall.callring.contracts.recording.RecordingFile(
                                        "content://synthetic/$attempts",
                                        "new.wav",
                                        1,
                                        1,
                                    ),
                                ),
                                SnapshotDomainStatus.COMPLETE,
                            )
                        pending +=
                            ((part.data as AiJson.Arr).values[1] as AiJson.Obj)["handle"].string()
                        if (mode == "changed") coordinator.runtimeChanged()
                        AiDomain.entries
                            .filter { mode != "budget" || it != AiDomain.D20 }
                            .associate {
                                it.name to
                                    if (it == AiDomain.D17) part else SnapshotPart(aiObject())
                            }
                    },
                )
            if (mode == "failed")
                sources += { _ ->
                    throw com.greatwall.callring.application.ports.BackendFailure(
                        ApiError(ErrorCode.IO_FAILURE, "test.source_failed"),
                    )
                }
            if (mode == "budget")
                sources += { _ -> mapOf("D20" to SnapshotPart(aiObject(), count = 20001)) }
            val result = AiSnapshotService(coordinator, sources, { emptyList() }).captureComplete()
            assertNotNull(resources.lookup(delivered))
            if (mode == "success") {
                assertTrue(result is ApiResult.Success)
                assertNotNull(resources.lookup(pending.single()))
            } else {
                assertTrue(mode, result is ApiResult.Failure)
                pending.forEach { assertTrue(mode, runCatching { resources.lookup(it) }.isFailure) }
                assertEquals(if (mode == "changed") 3 else 1, attempts)
            }
        }
    }

    @Test
    fun oversizedFullDirectoryRejectsBeforeAllocatingResourceHandles() {
        val resources = ForegroundResourceRegistry()
        val failure = runCatching {
            resources.snapshotPart(
                List(20001) {
                    com.greatwall.callring.contracts.recording.RecordingFile(
                        "content://synthetic/$it",
                        "file.wav",
                        1,
                        1,
                    )
                },
                SnapshotDomainStatus.COMPLETE,
            )
        }
            .exceptionOrNull()
        assertEquals(
            "ai.context_budget_exceeded",
            (failure as com.greatwall.callring.application.ports.BackendFailure).error.message,
        )
    }

    @Test
    fun fullStampsUseActualAppearanceRingtoneAndRuntimeDependencies() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        coordinator.publishResource(
            "preferences",
            setOf("appearance:colorMode"),
            ChangeImpact.wide(setOf(DataDomain.APPEARANCE)),
        )
        coordinator.publishRuntime(setOf("runtime", "runtime:capabilities"))
        val api =
            AiSnapshotService(
                coordinator,
                listOf({ _ -> AiDomain.entries.associate { it.name to SnapshotPart(aiObject()) } }),
                { emptyList() },
            )
        val stamps =
            api.captureComplete().requiredValue().domains.associate { it.id to it.sourceVersion }
        assertEquals(1L, stamps["D11"])
        assertEquals(2L, stamps["D12"])
        assertEquals(2L, stamps["D19"])
    }

    @Test
    fun threeRuntimeChangesCannotBeHiddenByThePublicRevisionVectorShape() = runBlocking {
        val coordinator = BusinessRevisionCoordinator()
        var reads = 0
        val initial = coordinator.capture()
        val api =
            AiSnapshotService(
                coordinator,
                listOf(
                    { _ ->
                        reads++
                        coordinator.runtimeChanged()
                        AiDomain.entries.associate {
                            it.name to
                                SnapshotPart(
                                    aiObject("sample" to aiNumber(reads)),
                                    scope = "live_probe",
                                )
                        }
                    },
                ),
                { emptyList() },
            )
        val result = api.captureComplete()
        assertEquals(initial, coordinator.capture())
        assertEquals(3, reads)
        assertTrue(result is ApiResult.Failure)
        assertEquals(ErrorCode.CONFLICT, (result as ApiResult.Failure).error.code)
    }
}
