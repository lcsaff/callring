package com.greatwall.callring.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.ui.designsystem.components.LocalPagerGestureGate
import com.greatwall.callring.ui.designsystem.components.PagerGestureGate
import com.greatwall.callring.ui.feature.callers.*
import com.greatwall.callring.ui.feature.conversation.ConversationViewModel
import com.greatwall.callring.ui.feature.home.*
import com.greatwall.callring.ui.feature.settings.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal val homeTabs = listOf(UiDestination.Home, UiDestination.Callers, UiDestination.Conversation, UiDestination.Settings)

/** Model creation is separate from Pager composition, including offscreen prefetch. */
internal class HomePageModels(
    val home: HomeViewModel?,
    val quiet: HomeQuietViewModel?,
    val callers: CallerListViewModel?,
    val conversation: ConversationViewModel?,
)

@Composable internal fun rememberHomePageModels(api: CallringUiApi, visited: Int): HomePageModels {
    val home: HomeViewModel? = if (visited and 1 != 0) uiViewModel("main-home") { HomeViewModel(api) } else null
    val quiet: HomeQuietViewModel? = if (visited and 1 != 0) uiViewModel("main-quiet") { HomeQuietViewModel(api.settings, api.changes, it) } else null
    val callers: CallerListViewModel? = if (visited and 2 != 0) uiViewModel("main-callers") {
        CallerListViewModel(api.callers::search, api::checkNumber, api.changes, it)
    } else null
    val conversation: ConversationViewModel? = if (visited and 4 != 0) uiViewModel("main-conversation") {
        ConversationViewModel(api, savedState = it)
    } else null
    return HomePageModels(home, quiet, callers, conversation)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HomePager(
    api: CallringUiApi,
    pager: PagerState,
    savedPages: SaveableStateHolder,
    models: HomePageModels,
    settings: SettingsSummaryViewModel?,
    gestures: PagerGestureGate,
    modal: Boolean,
    refreshSignal: Long,
    open: (UiDestination) -> Unit,
    onConversationModal: (Boolean) -> Unit,
    rootVisible: Boolean,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = rootVisible && pager.currentPage != 0 && !modal && !imeVisible) {
        scope.launch { pager.scrollToPage(0) }
    }
    LaunchedEffect(pager.settledPage) { focus.clearFocus() }
    DisposableEffect(Unit) { onDispose { focus.clearFocus() } }
    var confirmedPage by remember(pager) { mutableIntStateOf(pager.settledPage) }
    LaunchedEffect(pager, modal) {
        if (!modal) snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
            .distinctUntilChanged().collect { page -> if (page != null) confirmedPage = page }
    }
    LaunchedEffect(modal) {
        // An arriving modal must cancel a drag/fling before it commits another page.
        if (modal) pager.scrollToPage(confirmedPage)
    }
    val density = LocalDensity.current
    val edgeInsets = WindowInsets.systemGestures
    val left = edgeInsets.getLeft(density, LayoutDirection.Ltr)
    val right = edgeInsets.getRight(density, LayoutDirection.Ltr)
    CompositionLocalProvider(LocalPagerGestureGate provides gestures) {
        HorizontalPager(
            state = pager,
            key = { homeTabs[it].route },
            modifier = Modifier.fillMaxSize().testTag("home-pager").pointerInput(gestures, left, right) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val systemEdge = down.position.x < left || down.position.x >= size.width - right
                    if (systemEdge) gestures.acquire()
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (systemEdge) event.changes.forEach {
                                if (it.position != it.previousPosition) it.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    } finally { if (systemEdge) gestures.release() }
                }
            },
            beyondViewportPageCount = 1,
            userScrollEnabled = !modal && gestures.held == 0,
            flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1)),
        ) { page ->
            // Only actual content being displayed uses its saveable subtree. Prefetch stays inert.
            if (page == pager.currentPage || pager.isScrollInProgress) {
                savedPages.SaveableStateProvider(homeTabs[page].route) {
                    when (page) {
                        0 -> if (models.home != null && models.quiet != null) HomePage(
                            models.home, models.quiet, refreshSignal,
                            active = rootVisible && pager.settledPage == 0 && !pager.isScrollInProgress, open = open,
                        )
                        1 -> models.callers?.let { CallersPage(it, open) }
                        2 -> models.conversation?.let {
                            ConversationRoute(api, { open(UiDestination.Setting(SettingsPage.AI)) }, it,
                                active = rootVisible && pager.settledPage == 2 && !pager.isScrollInProgress,
                                onModalChanged = onConversationModal)
                        }
                        3 -> settings?.let { SettingsPage(it, open) }
                    }
                }
            }
        }
    }
}

@Composable private fun HomePage(model: HomeViewModel, quiet: HomeQuietViewModel, signal: Long, active: Boolean, open: (UiDestination) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val quietState by quiet.state.collectAsStateWithLifecycle()
    val mutation by quiet.mutation.collectAsStateWithLifecycle()
    val draft by quiet.draft.collectAsStateWithLifecycle()
    val dialog by quiet.dialogOpen.collectAsStateWithLifecycle()
    if (active) TimeRefresh(model.timeRefresh, signal, state.data?.currentWork?.nextBoundaryEpochMillis, 60_000, model::refresh)
    HomeScreen(state, model::refresh, { open(UiDestination.Leaves) }, quietCard = {
        HomeQuietCard(quietState, mutation, quiet::refresh, quiet::setEnabled, quiet::openEditor)
    }, onSetup = { open(UiDestination.Setup) })
    val saved = quietState.data
    val input = draft
    if (dialog && saved != null && input != null) HomeQuietDialog(saved, input, mutation, quiet::edit, quiet::save, quiet::closeEditor)
}

@Composable private fun CallersPage(model: CallerListViewModel, open: (UiDestination) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val check by model.check.collectAsStateWithLifecycle()
    CallerListScreen(state, query, check, model::search, model::page, model::refresh,
        { open(UiDestination.EditCaller()) }, { open(UiDestination.Report(it)) },
        { open(UiDestination.Report(it)) }, { open(UiDestination.CallHistory) })
}

@Composable private fun SettingsPage(model: SettingsSummaryViewModel, open: (UiDestination) -> Unit) {
    val summary by model.state.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    SettingsOverviewScreen(summary, mutation, null, model::refresh, model::statistics, { open(UiDestination.Setting(it)) })
}
