package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class SnapshotPart(
    val data: AiJson,
    val status: SnapshotDomainStatus = SnapshotDomainStatus.COMPLETE,
    val scope: String = "all_stored",
    val count: Long = if (data is AiJson.Arr) data.values.size.toLong() else 1,
    val publishResources: () -> Unit = {},
    val discardResources: () -> Unit = {},
)

class AiSnapshotService(
    private val coordinator: BusinessRevisionCoordinator,
    private val sources: List<suspend (Long) -> Map<String, SnapshotPart>>,
    private val keys: () -> List<String>,
    private val clock: Clock = Clock.systemUTC(),
) : AiSnapshotApi {
    override suspend fun captureComplete(): ApiResult<AiBusinessSnapshot> {
        repeat(3) {
            val parts = linkedMapOf<String, SnapshotPart>()
            var published = false
            try {
                val credentialSample =
                    try {
                        coordinator.guard {
                            Triple(
                                coordinator.capture(),
                                keys(),
                                AiDomain.entries.associateWith { coordinator.sourceVersion(it) },
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        throw BackendFailure(
                            ApiError(
                                ErrorCode.IO_FAILURE,
                                "ai.snapshot_domain_failed",
                                fieldErrors = mapOf("domains" to "D13,D14"),
                                retryable = true,
                            ),
                        )
                    }
                val before = credentialSample.first
                val sourceVersions = credentialSample.third
                val at = clock.millis()
                var bytes = 0L
                var rows = 0L
                for (source in sources) for ((id, part) in source(at)) {
                    require(!parts.containsKey(id)) { "snapshot.duplicate_domain" }
                    require(part.count >= 0) { "snapshot.invalid_count" }
                    parts[id] = part
                    bytes =
                        Math.addExact(
                            bytes,
                            part.data.encode().toByteArray(Charsets.UTF_8).size.toLong(),
                        )
                    rows = Math.addExact(rows, part.count)
                    if (bytes >= ContextBudget.REQUEST_BYTES || rows > 20000)
                        throw BackendFailure(
                            ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.context_budget_exceeded"),
                        )
                }
                if (
                    before != coordinator.capture() ||
                        sourceVersions.any { (domain, version) ->
                            coordinator.sourceVersion(domain) != version
                        }
                )
                    return@repeat
                val redactor = SecretRedactor(credentialSample.second)
                val domains =
                    parts.entries
                        .sortedBy { it.key }
                        .map { (id, part) ->
                            val safe = redactor.redact(part.data)
                            SnapshotDomain(
                                id,
                                if (
                                    part.status == SnapshotDomainStatus.COMPLETE && part.count == 0L
                                )
                                    SnapshotDomainStatus.EMPTY
                                else part.status,
                                part.count,
                                sourceVersions.getValue(AiDomain.valueOf(id)),
                                at,
                                part.scope,
                                digest(safe.encode()),
                                safe,
                            )
                        }
                if (
                    before != coordinator.capture() ||
                        sourceVersions.any { (domain, version) ->
                            coordinator.sourceVersion(domain) != version
                        }
                )
                    return@repeat
                val snapshot =
                    AiBusinessSnapshot(
                        UUID.randomUUID().toString(),
                        at,
                        ZoneId.systemDefault().id,
                        before,
                        domains,
                        redactor.count,
                    )
                parts.values.forEach { it.publishResources() }
                published = true
                return ApiResult.Success(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendFailure) {
                return ApiResult.Failure(e.error)
            } catch (_: Exception) {
                return ApiResult.Failure(
                    ApiError(ErrorCode.IO_FAILURE, "ai.snapshot_domain_failed", retryable = true),
                )
            } finally {
                if (!published) parts.values.forEach { it.discardResources() }
            }
        }
        return ApiResult.Failure(
            ApiError(ErrorCode.CONFLICT, "ai.snapshot_changed", retryable = true),
        )
    }
}

fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        "",
    ) {
        "%02x".format(it)
    }

fun AiBusinessSnapshot.json(): AiJson =
    aiObject(
        "schemaVersion" to aiNumber(schemaVersion),
        "snapshotId" to aiString(snapshotId),
        "capturedAt" to aiNumber(capturedAt),
        "zoneId" to aiString(zoneId),
        "revision" to
            aiObject(
                "incarnation" to aiString(revision.incarnation),
                "versions" to AiJson.Obj(revision.values.mapValues { aiNumber(it.value) }),
            ),
        "domains" to
            AiJson.Arr(
                domains.map {
                    aiObject(
                        "id" to aiString(it.id),
                        "status" to aiString(it.status.name),
                        "itemCount" to aiNumber(it.itemCount),
                        "sourceVersion" to aiNumber(it.sourceVersion),
                        "collectedAt" to aiNumber(it.collectedAt),
                        "sourceScope" to aiString(it.sourceScope),
                        "digest" to aiString(it.digest),
                        "data" to it.data,
                    )
                },
            ),
        "redactionCount" to aiNumber(redactedCount),
    )
