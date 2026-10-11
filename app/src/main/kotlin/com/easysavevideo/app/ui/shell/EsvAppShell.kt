package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.layout.*
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.easysavevideo.app.R

@Composable
fun EsvAppShell(controller: NavHostController, initial: EsvDestination,
    windowSize: WindowSizeClass, chromeVisible: Boolean, navigationEnabled: Boolean,
    onNavigate: (EsvDestination) -> Unit, content: @Composable (EsvDestination) -> Unit) {
    val entry by controller.currentBackStackEntryAsState()
    val destination = EsvDestination.fromRoute(entry?.destination?.route)
    val rail = windowSize.widthSizeClass != WindowWidthSizeClass.Compact ||
        windowSize.heightSizeClass == WindowHeightSizeClass.Compact
    val labels = mapOf(EsvDestination.DISCOVER to R.string.ui_discover,
        EsvDestination.TASKS to R.string.ui_tasks, EsvDestination.RECORD to R.string.ui_record,
        EsvDestination.LIBRARY to R.string.ui_library)
    val icons = mapOf(EsvDestination.DISCOVER to Icons.Default.Search,
        EsvDestination.TASKS to Icons.Default.List, EsvDestination.RECORD to Icons.Default.PlayArrow,
        EsvDestination.LIBRARY to Icons.Default.Menu)
    Scaffold(topBar = {
        if (chromeVisible) Row(Modifier.fillMaxWidth().statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onNavigate(EsvDestination.DISCOVER) }, enabled = navigationEnabled) {
                Text(stringResource(R.string.ui_app_name))
            }
            TextButton(onClick = { onNavigate(EsvDestination.SETTINGS) }, enabled = navigationEnabled) {
                Text(stringResource(R.string.ui_settings))
            }
        }
    }, bottomBar = {
        if (chromeVisible && !rail) NavigationBar {
            EsvDestination.roots.forEach { root ->
                val label = stringResource(labels.getValue(root))
                NavigationBarItem(selected = destination !in listOf(EsvDestination.SETTINGS, EsvDestination.SOURCES) && destination.root == root,
                    onClick = { onNavigate(root) }, enabled = navigationEnabled,
                    icon = { Icon(icons.getValue(root), contentDescription = null) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (chromeVisible && rail) NavigationRail {
                EsvDestination.roots.forEach { root ->
                    val label = stringResource(labels.getValue(root))
                    NavigationRailItem(selected = destination !in listOf(EsvDestination.SETTINGS, EsvDestination.SOURCES) && destination.root == root,
                        onClick = { onNavigate(root) }, enabled = navigationEnabled,
                        icon = { Icon(icons.getValue(root), contentDescription = null) }, label = { Text(label) })
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
