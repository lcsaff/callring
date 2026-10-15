package com.greatwall.callring.bootstrap

import android.content.Context

/** The UI Application implements this and owns exactly one lazily created graph. */
interface BackendProvider {
    val backendGraph: BackendGraph

    companion object {
        @JvmStatic
        fun from(context: Context): BackendProvider =
            context.applicationContext as? BackendProvider
                ?: throw IllegalStateException("The Application must implement BackendProvider")
    }
}
