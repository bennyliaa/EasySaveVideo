package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.easysavevideo.app.R

@Composable
fun EsvAppShell(controller: NavHostController, initial: EsvDestination,
    windowSize: WindowSizeClass, chromeVisible: Boolean, navigationEnabled: Boolean,
    onNavigate: (EsvDestination) -> Unit, content: @Composable (EsvDestination) -> Unit) {
    val entry by controller.currentBackStackEntryAsState()
    val destination = EsvDestination.fromRoute(entry?.destination?.route)
    val labels = mapOf(EsvDestination.DISCOVER to R.string.ui_discover,
        EsvDestination.TASKS to R.string.ui_tasks, EsvDestination.RECORD to R.string.ui_record,
        EsvDestination.LIBRARY to R.string.ui_library)
    val icons = mapOf(EsvDestination.DISCOVER to Icons.Default.Search,
        EsvDestination.TASKS to Icons.Default.List, EsvDestination.RECORD to Icons.Default.PlayArrow,
        EsvDestination.LIBRARY to Icons.Default.Menu)
    val headerScroll = rememberScrollState()
    val railScroll = rememberScrollState()
    val bottomScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val navigationInsets = NavigationBarDefaults.windowInsets
        val horizontalInsetsDp = with(density) {
            (navigationInsets.getLeft(this, direction) + navigationInsets.getRight(this, direction)).toDp().value
        }
        val layout = shellNavigationLayout(maxWidth.value, maxHeight.value, density.fontScale,
            wideWindow = windowSize.widthSizeClass != WindowWidthSizeClass.Compact,
            lowWindow = windowSize.heightSizeClass == WindowHeightSizeClass.Compact,
            horizontalInsetsDp = horizontalInsetsDp)
        Scaffold(topBar = {
            if (chromeVisible) Row(Modifier.fillMaxWidth().statusBarsPadding()
                .heightIn(max = layout.headerMaxHeightDp.dp).verticalScroll(headerScroll),
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onNavigate(EsvDestination.DISCOVER) }, enabled = navigationEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ui_app_name))
                }
                TextButton(onClick = { onNavigate(EsvDestination.SETTINGS) }, enabled = navigationEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ui_settings))
                }
            }
        }, bottomBar = {
            if (chromeVisible && !layout.rail) NavigationBar(
                modifier = Modifier.heightIn(max = layout.bottomMaxHeightDp.dp).verticalScroll(bottomScroll)) {
                EsvDestination.roots.forEach { root ->
                    val label = stringResource(labels.getValue(root))
                    NavigationBarItem(selected = destination !in listOf(EsvDestination.SETTINGS, EsvDestination.SOURCES) && destination.root == root,
                        onClick = { onNavigate(root) }, enabled = navigationEnabled,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        alwaysShowLabel = true,
                        icon = { Icon(icons.getValue(root), contentDescription = null) },
                        label = { Text(label, textAlign = TextAlign.Center, softWrap = true) })
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (chromeVisible && layout.rail) NavigationRail(
                    modifier = Modifier.width(layout.railWidthDp.dp).fillMaxHeight().verticalScroll(railScroll)) {
                    EsvDestination.roots.forEach { root ->
                        val label = stringResource(labels.getValue(root))
                        NavigationRailItem(selected = destination !in listOf(EsvDestination.SETTINGS, EsvDestination.SOURCES) && destination.root == root,
                            onClick = { onNavigate(root) }, enabled = navigationEnabled,
                            modifier = Modifier.fillMaxWidth().sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            alwaysShowLabel = true,
                            icon = { Icon(icons.getValue(root), contentDescription = null) },
                            label = { Text(label, textAlign = TextAlign.Center, softWrap = true) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(controller, startDestination = initial.route,
                        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                        EsvDestination.entries.forEach { route ->
                            composable(route.route) { content(route) }
                        }
                    }
                }
            }
        }
    }
}
