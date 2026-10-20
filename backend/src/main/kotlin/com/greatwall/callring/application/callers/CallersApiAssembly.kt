package com.greatwall.callring.application.callers

import com.greatwall.callring.contracts.callers.*

internal fun callersApi(queries: CallerQueryService, commands: CallerCommandService): CallersApi =
    object : CallersApi {
        override suspend fun search(query: CallerSearch) = queries.search(query)

        override suspend fun getEditor(number: String?) = queries.editor(number)

        override suspend fun getOverview(query: OverviewQuery) = queries.overview(query)

        override suspend fun getLatestCall() = queries.latest()

        override suspend fun getCallDetail(callId: Long) = queries.detail(callId)

        override suspend fun saveProfile(command: SaveCallerProfile) = commands.save(command)

        override suspend fun setWhitelisted(number: String, enabled: Boolean) =
            commands.whitelist(number, enabled)

        override suspend fun numberDataImpact(number: String) = commands.impact(number)

        override suspend fun deleteNumberCore(number: String, expectedCallCount: Int) =
            commands.delete(number, expectedCallCount)
    }
