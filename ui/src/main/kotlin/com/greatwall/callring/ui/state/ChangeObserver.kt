package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Register before initial load. Token comparison survives conflated intermediate commits. */
class ChangeObserver(private val feed: ChangeFeed, private val subscription: ChangeSubscription) {
    fun token(): InvalidationToken? = feed.snapshots()?.value?.token(subscription)
    fun start(scope: CoroutineScope, refresh: () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val snapshots = feed.snapshots()
        if (snapshots != null) snapshots.map { it.token(subscription) }.distinctUntilChanged().collect { refresh() }
        else feed.observe().collect { if (it.affects(subscription.domains)) refresh() }
    }
}
