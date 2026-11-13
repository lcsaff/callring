package com.greatwall.callring.ui.app

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.foundation.pager.PagerState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.greatwall.callring.ui.designsystem.components.PagerGestureGate
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowSizeClass
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.designsystem.*
import com.greatwall.callring.ui.feature.callers.*
import com.greatwall.callring.ui.feature.home.*
import com.greatwall.callring.ui.feature.report.*
import com.greatwall.callring.ui.feature.settings.*
import com.greatwall.callring.ui.feature.setup.*
import com.greatwall.callring.ui.feature.update.StartupUpdateViewModel
import com.greatwall.callring.ui.designsystem.components.ConfirmationDialog
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Email
import com.greatwall.callring.ui.designsystem.components.LocalFeedbackHost
import com.greatwall.callring.ui.designsystem.components.LocalFeedbackScope
import com.greatwall.callring.ui.designsystem.components.FeedbackSnackbar
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** The custom bottom bar replaces the M3 navigation suite only in compact windows. */
private val BottomBarContentHeight = 44.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CallringApp(
    api: CallringUiApi, requestedDestination: UiDestination = UiDestination.Home, requestSequence: Long = 0,
    refreshSignal: Long = 0, onCapability: (Capability) -> Unit,
    onPinWidget: () -> Unit, onAppSettings: () -> Unit,
    notice: UiNotice? = null, onNoticeConsumed: (Long) -> Unit = {},
) {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val settings: @Composable () -> SettingsSummaryViewModel = {
        uiViewModel("application-settings", owner) { SettingsSummaryViewModel(api) }
    }
    val pager = rememberSaveable(saver = Saver<PagerState, Int>(
        save = { it.settledPage },
        restore = { PagerState(currentPage = it.coerceIn(homeTabs.indices)) { homeTabs.size } },
    )) { PagerState(currentPage = homeTabs.indexOf(requestedDestination).coerceAtLeast(0)) { homeTabs.size } }
    val savedPages = rememberSaveableStateHolder()
    var visited by remember { mutableIntStateOf(1 shl pager.currentPage) }
    LaunchedEffect(pager) {
        snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
            .distinctUntilChanged().collect { page -> if (page != null) visited = visited or (1 shl page) }
    }
    val models = rememberHomePageModels(api, visited)
    val summary = if (visited and 8 != 0) settings() else null
    val quietOpen = models.quiet?.dialogOpen?.collectAsStateWithLifecycle()?.value == true
    var conversationModal by remember { mutableStateOf(false) }
    val gestures = remember { PagerGestureGate() }
    val pending by api.conversation.foreground.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val appearanceModel: AppearanceViewModel = uiViewModel("application-appearance") { AppearanceViewModel(api, it) }
    val navigationState: UiNavigationState = uiViewModel("application-navigation") { UiNavigationState(it) }
    val startupUpdate: StartupUpdateViewModel = uiViewModel("application-startup-update") { StartupUpdateViewModel(api.update) }
    val updateRelease by startupUpdate.release.collectAsStateWithLifecycle()
    val startupUpdateFinished by startupUpdate.finished.collectAsStateWithLifecycle()
    val appearance by appearanceModel.draft.collectAsStateWithLifecycle()
    val appearanceState by appearanceModel.state.collectAsStateWithLifecycle()
    CallringTheme((appearance ?: appearanceState.data ?: DefaultAppearance).rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
        ThemeWindowSupport()
        val navigation = rememberNavController()
        val entry by navigation.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val selectedRoute = homeTabs[pager.currentPage].route
        val modal = quietOpen || conversationModal || updateRelease != null || pending?.launched == true
        val select: (UiDestination) -> Unit = { destination ->
            if (!modal) scope.launch { pager.scrollToPage(homeTabs.indexOf(destination)) }
        }
        updateRelease?.let { release ->
            ConfirmationDialog(
                title = "发现新版本", message = "新版本 ${release.versionName} 已可用，是否前往更新界面？",
                confirmLabel = "确定", dismissLabel = "关闭",
                onConfirm = {
                    startupUpdate.dismiss()
                    navigation.navigate(UiDestination.Setting(SettingsPage.UPDATE).route) { launchSingleTop = true }
                },
                onDismiss = startupUpdate::dismiss,
            )
        }
        val tabs = listOf(UiDestination.Home to "首页", UiDestination.Callers to "档案", UiDestination.Conversation to "沟通", UiDestination.Settings to "设置")
        LaunchedEffect(requestSequence, modal) {
            // Preserve the request until the visible editor/confirmation can be dismissed.
            if (modal) return@LaunchedEffect
            navigation.currentBackStackEntryFlow.first()
            if (navigationState.consume(requestSequence) && (requestedDestination != UiDestination.Home || requestSequence != 0L)) {
                val page = homeTabs.indexOf(requestedDestination)
                if (page >= 0) {
                    navigation.popBackStack(UiDestination.Home.route, false)
                    pager.scrollToPage(page)
                } else navigation.openDestination(requestedDestination)
            }
        }
        val feedbackHost = remember { SnackbarHostState() }
        LaunchedEffect(notice?.id) {
            notice?.let { value ->
                feedbackHost.showSnackbar(value.text, withDismissAction = true)
                onNoticeConsumed(value.id)
            }
        }
        val adaptiveInfo = currentWindowAdaptiveInfo()
        val wideLayout = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
        val imeVisible = WindowInsets.isImeVisible
        val isTabRoute = route == UiDestination.Home.route
        CompositionLocalProvider(LocalFeedbackHost provides feedbackHost, LocalFeedbackScope provides rememberCoroutineScope()) {
            if (wideLayout) {
                NavigationSuiteScaffold(
                    layoutType = if (isTabRoute)
                        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo) else NavigationSuiteType.None,
                    navigationSuiteItems = {
                        tabs.forEachIndexed { index, (destination, title) -> item(
                            selected = selectedRoute == destination.route,
                            enabled = !modal,
                            modifier = Modifier.testTag("tab-${destination.route}"),
                            onClick = { select(destination) },
                            icon = { NavigationGlyph(index) }, label = { Text(title) },
                        ) }
                    },
                ) {
                    AppScaffold(api, settings, appearanceModel, navigation, refreshSignal, feedbackHost, {},
                        onCapability, onAppSettings, startupUpdateFinished,
                        home = { HomePager(api, pager, savedPages, models, summary, gestures, modal, refreshSignal,
                            { target -> navigation.openDestination(target) }, { conversationModal = it }, isTabRoute) })
                }
            } else {
                AppScaffold(api, settings, appearanceModel, navigation, refreshSignal, feedbackHost,
                    bottomBar = {
                        // 键盘弹出时隐藏常驻栏，只让输入区随键盘上移。
                        if (isTabRoute && !imeVisible) CallringBottomBar(tabs, selectedRoute, !modal, select)
                    },
                    onCapability = onCapability, onAppSettings = onAppSettings,
                    startupUpdateFinished = startupUpdateFinished,
                    home = { HomePager(api, pager, savedPages, models, summary, gestures, modal, refreshSignal,
                        { target -> navigation.openDestination(target) }, { conversationModal = it }, isTabRoute) })
            }
        }
    }
}

@Composable
private fun AppScaffold(
    api: CallringUiApi,
    settings: @Composable () -> SettingsSummaryViewModel,
    appearanceModel: AppearanceViewModel,
    navigation: NavHostController,
    refreshSignal: Long,
    feedbackHost: SnackbarHostState,
    bottomBar: @Composable () -> Unit,
    onCapability: (Capability) -> Unit,
    onAppSettings: () -> Unit,
    startupUpdateFinished: Boolean,
    home: @Composable () -> Unit,
) {
    fun open(destination: UiDestination) = navigation.openDestination(destination)
    val back: () -> Unit = { navigation.popBackStack() }
    Scaffold(modifier = Modifier, snackbarHost = { FeedbackSnackbar(feedbackHost) }, bottomBar = bottomBar) { insets ->
        NavHost(navigation, UiDestination.Home.route, Modifier.padding(insets).consumeWindowInsets(insets)) {
            composable("home") { home() }
            composable(UiDestination.Setup.route) {
                val model: SetupViewModel = uiViewModel { SetupViewModel(api) }
                val state by model.state.collectAsStateWithLifecycle()
                TimeRefresh(model.timeRefresh, refreshSignal, intervalMillis = 60_000, refresh = model::refresh)
                SetupScreen(state, back, model::refresh, onCapability, onAppSettings,
                    { open(UiDestination.Setting(SettingsPage.RECORDING)) },
                    { open(UiDestination.Setting(SettingsPage.RUNTIME)) })
            }
            composable(UiDestination.CallHistory.route) {
                CallHistoryRoute(api, back, { open(UiDestination.RecordingArchives) }, { open(UiDestination.CallHistoryDetail(it)) })
            }
            composable("call-history/{callId}", listOf(navArgument("callId") { type = NavType.LongType })) { record ->
                val callId = requireNotNull(record.arguments).getLong("callId")
                ArchiveRoute(api, callId, null, null, back, { open(UiDestination.EditCaller(it)) }, historyDetail = true)
            }

            composable("recording-archives") {
                RecordingArchivesRoute(api, refreshSignal, back) {
                    open(UiDestination.Archive(it.callId, it.number, it.startedAtEpochMillis))
                }
            }
            composable("lookup") {
                val model: NumberLookupViewModel = uiViewModel { NumberLookupViewModel(api::checkNumber, it, api.changes) }
                val text by model.text.collectAsStateWithLifecycle()
                val check by model.result.collectAsStateWithLifecycle()
                NumberLookupScreen(text, check, back, model::edit) {
                    model.checkedNumber()?.let { open(UiDestination.Report(it)) }
                }
            }
            composable("edit?number={number}&field={field}", listOf(
                navArgument("number") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("field") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { record ->
                val number = record.arguments?.getString("number")?.takeIf { it.isNotEmpty() }
                val field = number?.let {
                    val argument = record.arguments?.getString("field")
                    CallerProfileField.entries.firstOrNull { it.name == argument }
                }
                val model: CallerEditorViewModel = uiViewModel {
                    CallerEditorViewModel(number, api.callers, api.changes, it, saveWithBase = api.edits::profile,
                        deletion = if (field == null) api.numberDeletion else null, onDeleted = back, field = field)
                }
                val state by model.state.collectAsStateWithLifecycle()
                val draft by model.draft.collectAsStateWithLifecycle()
                val mutation by model.mutation.collectAsStateWithLifecycle()
                val dirty by model.dirty.collectAsStateWithLifecycle()
                val impact by model.impact.collectAsStateWithLifecycle()
                val deletionState by model.deletionState.collectAsStateWithLifecycle()
                val deletionError by model.deletionError.collectAsStateWithLifecycle()
                CallerEditorScreen(state, draft, mutation, number == null, back, model::refresh, { value -> model.edit { value } }, model::save,
                    onDeleteRequest = if (number != null && field == null) model::requestDeletion else null,
                    impact = impact, deletionState = deletionState, deletionError = deletionError,
                    onConfirmDelete = model::confirmDeletion, onDismissDelete = model::dismissDeletion,
                    field = field, onReload = model::reload, draftDirty = dirty.takeIf { field != null })
            }
            composable("report/{number}", listOf(navArgument("number") { type = NavType.StringType })) { record ->
                val number = requireNotNull(record.arguments?.getString("number"))
                val model: ReportViewModel = uiViewModel { ReportViewModel(number, api.reports, api.changes, it,
                    DateRange(LocalDate.now().minusDays(29), LocalDate.now())) }
                val history: CallHistoryViewModel = uiViewModel {
                    CallHistoryViewModel(api.callers, api.recordings, api.changes, number = number, saved = it)
                }
                val export = rememberExportPicker(model::export)
                TimeRefresh(model.timeRefresh, refreshSignal, intervalMillis = 60_000, refresh = { model.refresh() })
                NumberProfileScreen(
                    model, history, back, export,
                    onManageProfile = { open(UiDestination.EditCaller(number)) },
                    onCapability = onCapability,
                    onOpenCall = { open(UiDestination.CallHistoryDetail(it)) },
                    onEditField = { open(UiDestination.EditCaller(number, it)) },
                )
            }
            composable("archive/{callId}/{number}/{startedAt}", listOf(
                navArgument("callId") { type = NavType.LongType }, navArgument("number") { type = NavType.StringType },
                navArgument("startedAt") { type = NavType.LongType })) { record ->
                val args = requireNotNull(record.arguments)
                val number = requireNotNull(args.getString("number"))
                ArchiveRoute(api, args.getLong("callId"), number, args.getLong("startedAt"), back,
                    { open(UiDestination.EditCaller(it)) })
            }
            composable("leaves") {
                SettingsRoute(SettingsPage.LEAVES, api, settings, appearanceModel, refreshSignal, back, onCapability, onAppSettings)
            }
            SettingsPage.entries.forEach { page -> composable(UiDestination.Setting(page).route) {
                SettingsRoute(page, api, settings, appearanceModel, refreshSignal, back, onCapability, onAppSettings,
                    startupUpdateFinished = startupUpdateFinished,
                    onSetup = { open(UiDestination.Setup) })
            } }
        }
    }
}

private fun NavHostController.openDestination(destination: UiDestination) {
    val arguments = currentBackStackEntry?.arguments
    val sameSelection = when (destination) {
        is UiDestination.Report -> arguments?.getString("number") == destination.number
        is UiDestination.EditCaller ->
            arguments?.getString("number").orEmpty() == destination.number.orEmpty() &&
                arguments?.getString("field") == destination.field?.name
        is UiDestination.CallHistoryDetail -> arguments?.getLong("callId") == destination.callId
        else -> true
    }
    // Single-top navigation retains the entry's ViewModels, so only reuse the same selection.
    navigate(destination.route) { launchSingleTop = sameSelection }
}

@Composable
private fun CallringBottomBar(
    tabs: List<Pair<UiDestination, String>>,
    route: String?,
    enabled: Boolean,
    onSelect: (UiDestination) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("bottom-bar"),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BottomBarContentHeight),
        ) {
            tabs.forEachIndexed { index, (destination, title) ->
                val selected = route == destination.route
                val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = BottomBarContentHeight)
                        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = { onSelect(destination) })
                        .testTag("tab-${destination.route}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        when (index) {
                            0 -> Icons.Filled.Home; 1 -> Icons.Filled.Person
                            2 -> Icons.Filled.Email; else -> Icons.Filled.Settings
                        },
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(title, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun NavigationGlyph(index: Int) {
    Icon(when (index) { 0 -> Icons.Filled.Home; 1 -> Icons.Filled.Person; 2 -> Icons.Filled.Email; else -> Icons.Filled.Settings }, contentDescription = null)
}

data class UiNotice(val id: Long, val text: String)
