package com.greatwall.callring.ui.app

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onPreviewCall: (CallDetail) -> Unit, onPinWidget: () -> Unit, onAppSettings: () -> Unit,
    notice: UiNotice? = null, onNoticeConsumed: (Long) -> Unit = {},
    onTestOverlay: () -> Unit = {},
) {
    val settings: SettingsSummaryViewModel = uiViewModel("application-settings") { SettingsSummaryViewModel(api) }
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
        LaunchedEffect(requestSequence) {
            navigation.currentBackStackEntryFlow.first()
            if (navigationState.consume(requestSequence) && (requestedDestination != UiDestination.Home || requestSequence != 0L)) {
                navigation.navigate(requestedDestination.route) { launchSingleTop = true }
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
        val isTabRoute = tabs.any { it.first.route == route }
        CompositionLocalProvider(LocalFeedbackHost provides feedbackHost, LocalFeedbackScope provides rememberCoroutineScope()) {
            if (wideLayout) {
                NavigationSuiteScaffold(
                    layoutType = if (isTabRoute)
                        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo) else NavigationSuiteType.None,
                    navigationSuiteItems = {
                        tabs.forEachIndexed { index, (destination, title) -> item(
                            selected = route == destination.route,
                            modifier = Modifier.testTag("tab-${destination.route}"),
                            onClick = { selectTab(navigation, destination) },
                            icon = { NavigationGlyph(index) }, label = { Text(title) },
                        ) }
                    },
                ) {
                    AppScaffold(api, settings, appearanceModel, navigation, refreshSignal, feedbackHost, {},
                        onCapability, onAppSettings, onTestOverlay, startupUpdateFinished)
                }
            } else {
                AppScaffold(api, settings, appearanceModel, navigation, refreshSignal, feedbackHost,
                    bottomBar = {
                        // 键盘弹出时隐藏常驻栏，只让输入区随键盘上移。
                        if (isTabRoute && !imeVisible) CallringBottomBar(tabs, route) { destination -> selectTab(navigation, destination) }
                    },
                    onCapability = onCapability, onAppSettings = onAppSettings, onTestOverlay = onTestOverlay,
                    startupUpdateFinished = startupUpdateFinished)
            }
        }
    }
}

@Composable
private fun AppScaffold(
    api: CallringUiApi,
    settings: SettingsSummaryViewModel,
    appearanceModel: AppearanceViewModel,
    navigation: NavHostController,
    refreshSignal: Long,
    feedbackHost: SnackbarHostState,
    bottomBar: @Composable () -> Unit,
    onCapability: (Capability) -> Unit,
    onAppSettings: () -> Unit,
    onTestOverlay: () -> Unit,
    startupUpdateFinished: Boolean,
) {
    fun open(destination: UiDestination) { navigation.navigate(destination.route) { launchSingleTop = true } }
    val back: () -> Unit = { navigation.popBackStack() }
    Scaffold(modifier = Modifier, snackbarHost = { FeedbackSnackbar(feedbackHost) }, bottomBar = bottomBar) { insets ->
        NavHost(navigation, UiDestination.Home.route, Modifier.padding(insets).consumeWindowInsets(insets)) {
            composable("home") {
                val model: HomeViewModel = uiViewModel { HomeViewModel(api) }
                val quiet: HomeQuietViewModel = uiViewModel { HomeQuietViewModel(api.settings, api.changes, it) }
                val state by model.state.collectAsStateWithLifecycle()
                val quietState by quiet.state.collectAsStateWithLifecycle()
                val quietMutation by quiet.mutation.collectAsStateWithLifecycle()
                val quietDraft by quiet.draft.collectAsStateWithLifecycle()
                val quietOpen by quiet.dialogOpen.collectAsStateWithLifecycle()
                TimeRefresh(model.timeRefresh, refreshSignal, state.data?.currentWork?.nextBoundaryEpochMillis, 60_000, model::refresh)
                HomeScreen(state, model::refresh, { open(UiDestination.Leaves) }, quietCard = {
                    HomeQuietCard(quietState, quietMutation, quiet::refresh, quiet::setEnabled, quiet::openEditor)
                }, onSetup = { open(UiDestination.Setup) })
                val saved = quietState.data
                val draft = quietDraft
                if (quietOpen && saved != null && draft != null) {
                    HomeQuietDialog(saved, draft, quietMutation, quiet::edit, quiet::save, quiet::closeEditor)
                }
            }
            composable(UiDestination.Setup.route) {
                val model: SetupViewModel = uiViewModel { SetupViewModel(api) }
                val state by model.state.collectAsStateWithLifecycle()
                TimeRefresh(model.timeRefresh, refreshSignal, intervalMillis = 60_000, refresh = model::refresh)
                SetupScreen(state, back, model::refresh, onCapability, onAppSettings,
                    { open(UiDestination.Setting(SettingsPage.RECORDING)) },
                    { open(UiDestination.Setting(SettingsPage.RUNTIME)) })
            }
            composable("callers") {
                val model: CallerListViewModel = uiViewModel { CallerListViewModel(api.callers::search, api::checkNumber, api.changes, it) }
                val state by model.state.collectAsStateWithLifecycle()
                val query by model.query.collectAsStateWithLifecycle()
                val check by model.check.collectAsStateWithLifecycle()
                CallerListScreen(state, query, check, model::search, model::page, { model.refresh() },
                    { open(UiDestination.EditCaller()) }, { open(UiDestination.Report(it)) },
                    { open(UiDestination.Report(it)) }, { open(UiDestination.Positions) })
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
            composable("edit?number={number}", listOf(navArgument("number") { type = NavType.StringType; nullable = true; defaultValue = null })) { record ->
                val number = record.arguments?.getString("number")?.takeIf { it.isNotEmpty() }
                val model: CallerEditorViewModel = uiViewModel { CallerEditorViewModel(number, api.callers, api.changes, it, saveWithBase = api.edits::profile, deletion = api.numberDeletion, onDeleted = back) }
                val state by model.state.collectAsStateWithLifecycle()
                val draft by model.draft.collectAsStateWithLifecycle()
                val mutation by model.mutation.collectAsStateWithLifecycle()
                val impact by model.impact.collectAsStateWithLifecycle()
                val deletionState by model.deletionState.collectAsStateWithLifecycle()
                val deletionError by model.deletionError.collectAsStateWithLifecycle()
                CallerEditorScreen(state, draft, mutation, number == null, back, model::refresh, { value -> model.edit { value } }, model::save,
                    onDeleteRequest = if (number != null) model::requestDeletion else null,
                    impact = impact, deletionState = deletionState, deletionError = deletionError,
                    onConfirmDelete = model::confirmDeletion, onDismissDelete = model::dismissDeletion)
            }
            composable("report/{number}", listOf(navArgument("number") { type = NavType.StringType })) { record ->
                val number = requireNotNull(record.arguments?.getString("number"))
                val model: ReportViewModel = uiViewModel { ReportViewModel(number, api.reports, api.changes, it,
                    DateRange(LocalDate.now().minusDays(29), LocalDate.now())) }
                val state by model.state.collectAsStateWithLifecycle()
                val query by model.query.collectAsStateWithLifecycle()
                val mutation by model.mutation.collectAsStateWithLifecycle()
                val export = rememberExportPicker(model::export)
                TimeRefresh(model.timeRefresh, refreshSignal, intervalMillis = 60_000, refresh = { model.refresh() })
                ReportScreen(state, query, mutation, back, { model.refresh() }, model::range, model::filter, model::page,
                    export, { open(UiDestination.EditCaller(number)) }, onCapability,
                    { open(UiDestination.Archive(it.id, number, it.startedAtEpochMillis)) })
            }
            composable("archive/{callId}/{number}/{startedAt}", listOf(
                navArgument("callId") { type = NavType.LongType }, navArgument("number") { type = NavType.StringType },
                navArgument("startedAt") { type = NavType.LongType })) { record ->
                val args = requireNotNull(record.arguments)
                val number = requireNotNull(args.getString("number"))
                ArchiveRoute(api, args.getLong("callId"), number, args.getLong("startedAt"), refreshSignal, back,
                    { open(UiDestination.EditCaller(number)) })
            }
            composable("settings") {
                val summary by settings.state.collectAsStateWithLifecycle()
                val mutation by settings.mutation.collectAsStateWithLifecycle()
                SettingsOverviewScreen(summary, mutation, null, settings::refresh, settings::statistics, settings::overlay,
                    { open(UiDestination.Setting(it)) })
            }
            composable("conversation") { ConversationRoute(api, onOpenSettings = { open(UiDestination.Setting(SettingsPage.AI)) }) }
            composable("leaves") {
                SettingsRoute(SettingsPage.LEAVES, api, settings, appearanceModel, refreshSignal, back, onCapability, onAppSettings)
            }
            composable("positions") {
                SettingsRoute(SettingsPage.POSITIONS, api, settings, appearanceModel, refreshSignal, back, onCapability, onAppSettings)
            }
            SettingsPage.entries.forEach { page -> composable(UiDestination.Setting(page).route) {
                SettingsRoute(page, api, settings, appearanceModel, refreshSignal, back, onCapability, onAppSettings,
                    onTestOverlay = onTestOverlay, startupUpdateFinished = startupUpdateFinished,
                    onSetup = { open(UiDestination.Setup) })
            } }
        }
    }
}

@Composable
private fun CallringBottomBar(
    tabs: List<Pair<UiDestination, String>>,
    route: String?,
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
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
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

private fun selectTab(navigation: NavHostController, destination: UiDestination) {
    navigation.navigate(destination.route) {
        popUpTo(navigation.graph.startDestinationId) { saveState = true }
        launchSingleTop = true; restoreState = true
    }
}

@Composable private fun NavigationGlyph(index: Int) {
    Icon(when (index) { 0 -> Icons.Filled.Home; 1 -> Icons.Filled.Person; 2 -> Icons.Filled.Email; else -> Icons.Filled.Settings }, contentDescription = null)
}

data class UiNotice(val id: Long, val text: String)
