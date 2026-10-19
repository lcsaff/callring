package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createCallersApi(context: BackendContext): CallersApi =
    with(context) {
        object : CallersApi {
            override suspend fun getCallDetail(callId: Long): ApiResult<CallDetail> = io { read { s -> detail(s, s.calls[callId] ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "call.not_found"))) } }
            override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> = io {
                page(query.page)
                read { s ->
                    val text = query.text.trim()
                    val rows =
                        s.profiles.values
                            .filter {
                                it.number.contains(text, true) ||
                                    it.name.contains(text, true) ||
                                    it.department.contains(text, true)
                            }
                            .sortedBy { it.number }
                            .map { profile(s, it.number) }
                    paged(rows, query.page, meta())
                }
            }

            override suspend fun getEditor(number: String?): ApiResult<CallerEditorData> = io {
                val normalized = number?.let { number(it) }
                read { s ->
                    val p = normalized?.let { s.profiles[it] }
                    CallerEditorData(
                        CallerDraft(
                            normalized.orEmpty(),
                            p?.name.orEmpty(),
                            p?.department.orEmpty(),
                            p?.positionId,
                            p?.whitelisted ?: false,
                        ),
                        frozenList(options(s).filter { it.active || it.id == p?.positionId }),
                        s.positions[s.myPositionId]?.let { option(s, it) },
                    )
                }
            }

            override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> = io {
                val n = number(query.number)
                val status = runtimeStatus()
                read { s ->
                    val calls = visible(s, n)
                    val end = saturatedAdd(clock.millis(), 1)
                    val range =
                        TimeRange(
                            saturatedSubtract(end, s.score.observationDays * 86400000L),
                            end,
                            currentSchedule(s).zoneId,
                        )
                    CallerOverview(
                        profile(s, n),
                        score(s, calls, range),
                        calls.count { it.counted }.toLong(),
                        calls.firstOrNull(),
                        WorkClassifier.status(s, query.workAtEpochMillis ?: clock.millis()),
                        s.quiet.mode,
                        checkNumber(n),
                        quality(s, calls, status),
                        meta(),
                    )
                }
            }

            override suspend fun getLatestCall(): ApiResult<CallDetail?> = io {
                read { latest(it) }
            }

            override suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile> =
                write {
                    val n = number(command.number)
                    mutate { s ->
                        val previous = s.profiles[n]
                        val dirty=command.dirtyFields
                        val base=command.baseline
                        requireField(dirty==null||(base!=null&&(base.number.isEmpty()||base.number==n)&&dirty.all{it in setOf("name","department","positionId","whitelisted")}),"baseline","draft.baseline_required")
                        fun <T> merged(field:String,wanted:T,current:T,baseline:T):T {
                            if(dirty==null)return wanted
                            if(field !in dirty)return current
                            requireField(baseline==current,field,"draft.field_changed")
                            return wanted
                        }
                        val name=merged("name",command.name,previous?.name.orEmpty(),base?.name.orEmpty())
                        val department=merged("department",command.department,previous?.department.orEmpty(),base?.department.orEmpty())
                        val position=merged("positionId",command.positionId,previous?.positionId,base?.positionId)
                        val whitelist=merged("whitelisted",command.whitelisted?:previous?.whitelisted?:false,previous?.whitelisted?:false,base?.whitelisted?:false)
                        validatePosition(s, position, previous?.positionId)
                        s.profiles[n] =
                            ProfileRecord(
                                n,
                                name.trim(),
                                department.trim(),
                                position,
                                whitelist,
                            )
                        profile(s, n)
                    }
                }

            override suspend fun setWhitelisted(
                number: String,
                enabled: Boolean,
            ): ApiResult<CallerProfile> = write {
                val n = number(number)
                mutate { s ->
                    s.profiles[n] = (s.profiles[n] ?: ProfileRecord(n)).copy(whitelisted = enabled)
                    profile(s, n)
                }
            }
        }
    }
