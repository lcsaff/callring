package com.greatwall.callring.application

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun createHomeApi(context: BackendContext): HomeApi =
    with(context) {
        object : HomeApi {
            override suspend fun load(): ApiResult<HomeData> = io {
                val ring = audio.getSettings()
                val runtime = runtimeStatus()
                read { s ->
                    HomeData(
                        ring,
                        runtime,
                        WorkClassifier.status(s, clock.millis()).toContract(),
                        latest(s),
                        stats(s),
                        s.overlayEnabled,
                        s.quiet.mode.toContract(),
                        meta(),
                    )
                }
            }
        }
    }
