package com.greatwall.callring.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.*
import com.greatwall.callring.ui.feature.callers.*
import com.greatwall.callring.ui.feature.home.*
import com.greatwall.callring.ui.feature.report.*
import com.greatwall.callring.ui.feature.settings.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import com.greatwall.callring.ui.designsystem.components.LocalFeedbackHost
import com.greatwall.callring.ui.designsystem.components.LocalFeedbackScope
import com.greatwall.callring.ui.designsystem.components.FeedbackSnackbar
import java.time.LocalDate

@Composable fun CallringApp(
    api: CallringUiApi, requestedDestination: UiDestination = UiDestination.Home, requestSequence: Long = 0,
    refreshSignal: Long = 0, onCapability: (Capability) -> Unit,
    onPreviewCall: (CallDetail) -> Unit, onPinWidget: () -> Unit, onAppSettings: () -> Unit,
    notice: UiNotice? = null, onNoticeConsumed: (Long) -> Unit = {},
) {
    val settings: SettingsSummaryViewModel = uiViewModel("application-settings") { SettingsSummaryViewModel(api) }
    val navigationState: UiNavigationState = uiViewModel("application-navigation") { UiNavigationState(it) }
    val summary by settings.state.collectAsStateWithLifecycle()
    ActiveRefresh(refreshSignal, refresh = settings::refresh)
    CallringTheme(summary.data?.appearance ?: DefaultAppearance) {
        ThemeWindowSupport()
        val navigation = rememberNavController()
        val entry by navigation.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val tabs = listOf(UiDestination.Home to "首页", UiDestination.Callers to "档案", UiDestination.Settings to "设置")
        LaunchedEffect(requestSequence) {
            if (navigationState.consume(requestSequence) && (requestedDestination != UiDestination.Home || requestSequence != 0L)) {
                navigation.navigate(requestedDestination.route) { launchSingleTop = true }
            }
        }
        fun open(destination: UiDestination) { navigation.navigate(destination.route) { launchSingleTop = true } }
        val back: () -> Unit = { navigation.popBackStack() }
        val feedbackHost = remember { SnackbarHostState() }
        LaunchedEffect(notice?.id) {
            notice?.let { value ->
                feedbackHost.showSnackbar(value.text, withDismissAction = true)
                onNoticeConsumed(value.id)
            }
        }
        val adaptiveInfo = currentWindowAdaptiveInfo()
        CompositionLocalProvider(LocalFeedbackHost provides feedbackHost, LocalFeedbackScope provides rememberCoroutineScope()) {
        NavigationSuiteScaffold(
            layoutType = if (tabs.any { it.first.route == route })
                NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo) else NavigationSuiteType.None,
            navigationSuiteItems = {
                tabs.forEachIndexed { index, (destination, title) -> item(
                    selected = route == destination.route,
                    modifier = Modifier.testTag("tab-${destination.route}"),
                    onClick = { navigation.navigate(destination.route) {
                        popUpTo(navigation.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true; restoreState = true
                    } }, icon = { NavigationGlyph(index) }, label = { Text(title) },
                ) }
            },
        ) {
        Scaffold(modifier = Modifier.imePadding(), snackbarHost = { FeedbackSnackbar(feedbackHost) }) { insets ->
            NavHost(navigation, UiDestination.Home.route, Modifier.padding(insets).consumeWindowInsets(insets)) {
                composable("home") {
                    val model: HomeViewModel = uiViewModel { HomeViewModel(api) }
                    val state by model.state.collectAsStateWithLifecycle()
                    val mutation by model.mutation.collectAsStateWithLifecycle()
                    val latestPreview by model.latestPreview.collectAsStateWithLifecycle()
                    val pendingPreview by model.pendingPreview.collectAsStateWithLifecycle()
                    LaunchedEffect(pendingPreview) {
                        pendingPreview?.let { model.consumePreview(); onPreviewCall(it) }
                    }
                    ActiveRefresh(refreshSignal, state.data?.currentWork?.nextBoundaryEpochMillis, 60_000, model::refresh)
                    HomeScreen(state, mutation, model::refresh, model::statistics, model::overlay, model::ringtone,
                        { open(UiDestination.Setting(SettingsPage.QUIET)) }, { open(UiDestination.Setting(SettingsPage.RUNTIME)) },
                        { open(UiDestination.Setting(SettingsPage.RINGTONE)) }, { open(UiDestination.Report(it)) }, { open(UiDestination.Settings) },
                        latestPreview, model::previewLatest, onPinWidget)
                }
                composable("callers") {
                    val model: CallerListViewModel = uiViewModel { CallerListViewModel(api.callers::search, api.changes, it) }
                    val state by model.state.collectAsStateWithLifecycle()
                    val query by model.query.collectAsStateWithLifecycle()
                    ActiveRefresh(refreshSignal, refresh = { model.refresh() })
                    CallerListScreen(state, query, model::search, model::page, { model.refresh() },
                        { open(UiDestination.EditCaller()) }, { open(UiDestination.Report(it)) }, { open(UiDestination.Lookup) })
                }
                composable("lookup") {
                    val model: NumberLookupViewModel = uiViewModel { NumberLookupViewModel(api::checkNumber, it) }
                    val text by model.text.collectAsStateWithLifecycle()
                    NumberLookupScreen(text, model.result(), back, model::edit) {
                        model.checkedNumber()?.let { open(UiDestination.Report(it)) }
                    }
                }
                composable("edit?number={number}", listOf(navArgument("number") { type = NavType.StringType; nullable = true; defaultValue = null })) { record ->
                    val number = record.arguments?.getString("number")?.takeIf { it.isNotEmpty() }
                    val model: CallerEditorViewModel = uiViewModel { CallerEditorViewModel(number, api.callers, api.changes, it) }
                    val state by model.state.collectAsStateWithLifecycle()
                    val draft by model.draft.collectAsStateWithLifecycle()
                    val mutation by model.mutation.collectAsStateWithLifecycle()
                    ActiveRefresh(refreshSignal, refresh = model::refresh)
                    CallerEditorScreen(state, draft, mutation, number == null, back, model::refresh, { value -> model.edit { value } }, model::save)
                }
                composable("report/{number}", listOf(navArgument("number") { type = NavType.StringType })) { record ->
                    val number = requireNotNull(record.arguments?.getString("number"))
                    val model: ReportViewModel = uiViewModel { ReportViewModel(number, api.reports, api.changes, it,
                        DateRange(LocalDate.now().minusDays(29), LocalDate.now())) }
                    val state by model.state.collectAsStateWithLifecycle()
                    val query by model.query.collectAsStateWithLifecycle()
                    val mutation by model.mutation.collectAsStateWithLifecycle()
                    val export = rememberExportPicker(model::export)
                    ActiveRefresh(refreshSignal, intervalMillis = 60_000, refresh = { model.refresh() })
                    ReportScreen(state, query, mutation, back, { model.refresh() }, model::range, model::filter, model::page,
                        export, { open(UiDestination.EditCaller(number)) }, onCapability)
                }
                composable("settings") {
                    val mutation by settings.mutation.collectAsStateWithLifecycle()
                    SettingsOverviewScreen(summary, mutation, null, settings::refresh, settings::statistics, settings::overlay,
                        { open(UiDestination.Setting(it)) })
                }
                SettingsPage.entries.forEach { page -> composable(UiDestination.Setting(page).route) {
                    SettingsRoute(page, api, settings, refreshSignal, back, onCapability, onAppSettings)
                } }
            }
        }
        }
        }
    }
}

@Composable private fun NavigationGlyph(index: Int) {
    Icon(when (index) { 0 -> Icons.Filled.Home; 1 -> Icons.Filled.Person; else -> Icons.Filled.Settings }, contentDescription = null)
}

data class UiNotice(val id: Long, val text: String)
