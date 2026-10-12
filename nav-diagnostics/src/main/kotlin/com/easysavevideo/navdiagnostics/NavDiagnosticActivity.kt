package com.easysavevideo.navdiagnostics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.easysavevideo.app.ui.shell.navigation.RootNavigation
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent sandbox: no ESV classes/data, networking, services, media or permissions. */
class NavDiagnosticActivity : ComponentActivity() {
    private val routes = listOf("discover", "tasks", "record", "library", "settings", "sources", "browser", "editor")
    private val roots = listOf("discover", "tasks", "record", "library")
    private val records = mutableListOf<JSONObject>()
    private var instance = 0
    private fun fixed(route: String?) = route?.takeIf { it in routes } ?: "NONE"
    private fun emit(action: String, nav: NavHostController, expected: String? = null) {
        if (records.size >= 1_000) return
        val current = fixed(nav.currentDestination?.route)
        val row = JSONObject().put("carrier_version", 3).put("action", action).put("instance", instance).put("route", current)
            .put("previous", fixed(nav.previousBackStackEntry?.destination?.route))
        if (expected != null) row.put("expected", expected).put("route_matches", current == expected)
        val present = JSONArray()
        for (r in routes) try { nav.getBackStackEntry(r); present.put(r) } catch (_: IllegalArgumentException) { }
        row.put("present_routes", present)
        val markers = JSONObject()
        for (r in roots) try { markers.put(r, nav.getBackStackEntry(r).savedStateHandle.get<Int>("owned_probe") ?: 0) } catch (_: IllegalArgumentException) { }
        row.put("owned_markers", markers)
        records.add(row)
        // Only this separate app's internal file; fixed route/action diagnostics, no arguments.
        val file = File(filesDir, "nav-observations-v3.jsonl")
        if (file.length() < 1_048_576) file.appendText(row.toString() + "\n")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = (savedInstanceState?.getInt("qa_instance") ?: 0) + 1
        setContent {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            var running by remember { mutableStateOf(false) }
            var useFixed by rememberSaveable { mutableStateOf(false) }
            val entry by nav.currentBackStackEntryAsState()
            var summary by remember { mutableStateOf("Runtime cases NOT RUN. This is not ESV media acceptance.") }
            fun original(target: String, action: String) {
                emit(action + "_BEFORE", nav)
                // Exact c18cb37 route equality + NavOptions; no proposed fix is applied.
                if (nav.currentDestination?.route != target) nav.navigate(target) {
                    launchSingleTop = true
                    if (target in roots) {
                        popUpTo("discover") { saveState = true }
                        restoreState = true
                    }
                }
                emit(action + "_AFTER", nav, target)
            }
            fun repaired(target: String, action: String) {
                emit("FIXED_" + action + "_BEFORE", nav)
                if (nav.currentDestination?.route != target) {
                    if (target in roots) RootNavigation.navigate(target, { nav.currentDestination?.route }, { nav.popBackStack() }) { preserve ->
                        nav.navigate(target) {
                            launchSingleTop = true
                            if (preserve) popUpTo("discover") { saveState = true }
                            else popUpTo(nav.graph.id) { saveState = false }
                            restoreState = true
                        }
                    } else nav.navigate(target) { launchSingleTop = true }
                }
                emit("FIXED_" + action + "_AFTER", nav, target)
            }
            fun selected(target: String, action: String) {
                if (useFixed) repaired(target, action) else original(target, action)
            }
            DisposableEffect(nav) {
                val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, _, _ -> emit("DESTINATION_CALLBACK", nav) }
                nav.addOnDestinationChangedListener(listener)
                onDispose { nav.removeOnDestinationChangedListener(listener) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("ESV Nav QA: separate sandbox, no media/user data")
                BasicText("Manual policy: " + if (useFixed) "FIXED" else "ORIGINAL")
                Control("MODE_ORIGINAL", !running) { useFixed = false }
                Control("MODE_FIXED", !running) { useFixed = true }
                Control("HEADER_DISCOVER", !running) { selected("discover", "HEADER_DISCOVER") }
                Control("OPEN_SETTINGS", !running) { selected("settings", "OPEN_SETTINGS") }
                for (root in roots) Control("BOTTOM_" + root.uppercase(), !running) { selected(root, "BOTTOM_" + root.uppercase()) }
                for (route in listOf("sources", "browser", "editor")) Control("OPEN_" + route.uppercase(), !running) { selected(route, "OPEN_" + route.uppercase()) }
                Control("BACK_POP", !running) { emit("BACK_BEFORE", nav); nav.popBackStack(); emit("BACK_AFTER", nav) }
                Control("RUN_ORIGINAL_MATRIX", !running) {
                    scope.launch {
                        running = true
                        try {
                            val cases = listOf(Triple("discover", "settings", "HEADER_DISCOVER"), Triple("discover", "settings", "BOTTOM_DISCOVER"),
                                Triple("tasks", "settings", "BOTTOM_TASKS"), Triple("discover", "sources", "SOURCE_ROOT"),
                                Triple("discover", "browser", "BROWSER_ROOT"), Triple("library", "editor", "EDITOR_ROOT"))
                            for ((root, auxiliary, name) in cases) {
                                // Reset only this owned graph; never touch the real ESV process/catalog.
                                for (r in roots) nav.clearBackStack(r)
                                nav.navigate("discover") { popUpTo(nav.graph.id) { inclusive = false }; restoreState = false }
                                delay(200)
                                if (root != "discover") original(root, "CASE_ROOT")
                                delay(200)
                                val rootEntry = nav.getBackStackEntry(root)
                                rootEntry.savedStateHandle["owned_probe"] = 17
                                original(auxiliary, "CASE_AUXILIARY"); delay(200)
                                original(root, name); delay(200)
                                val marker = try { nav.getBackStackEntry(root).savedStateHandle.get<Int>("owned_probe") == 17 } catch (_: IllegalArgumentException) { false }
                                emit(name + "_SETTLED", nav, root)
                                summary = name + ": owned root marker retained=" + marker
                            }
                            original("discover", "SAME_ROOT_FIRST"); delay(200); original("discover", "SAME_ROOT_REPEAT")
                            summary = "Original matrix executed; inspect actual route_matches/callbacks. No fix assumed."
                        } catch (error: Exception) {
                            summary = "Runtime stopped: " + error.javaClass.simpleName
                            emit("CASE_EXCEPTION", nav)
                        } finally { running = false }
                    }
                }
                Control("RUN_FIXED_MATRIX", !running) {
                    scope.launch {
                        running = true; useFixed = true
                        try {
                            // Same six v2 cases plus all auxiliaries to other roots. Fixture reset
                            // belongs only to this sandbox; assertions use actual controller state.
                            val cases = listOf(Triple("discover", "settings", "discover"), Triple("discover", "settings", "discover"),
                                Triple("tasks", "settings", "tasks"), Triple("discover", "sources", "discover"),
                                Triple("discover", "browser", "discover"), Triple("library", "editor", "library")) +
                                listOf("settings", "sources", "browser", "editor").flatMap { auxiliary ->
                                    roots.filter { it != "tasks" }.map { Triple("tasks", auxiliary, it) }
                                }
                            for ((index, case) in cases.withIndex()) {
                                val (root, auxiliary, target) = case
                                for (r in roots) nav.clearBackStack(r)
                                nav.navigate("discover") { popUpTo(nav.graph.id) { inclusive = false }; restoreState = false }
                                delay(200)
                                if (root != "discover") repaired(root, "CASE_ROOT")
                                delay(200)
                                nav.getBackStackEntry(root).savedStateHandle["owned_probe"] = 17
                                repaired(auxiliary, "CASE_AUXILIARY"); delay(200)
                                repaired(target, "CASE_${index}_ROOT"); delay(200)
                                emit("FIXED_CASE_${index}_SETTLED", nav, target)
                                repaired(root, "CASE_${index}_RETURN_ORIGINAL_ROOT"); delay(200)
                                emit("FIXED_CASE_${index}_MARKER_RESTORE", nav, root)
                            }
                            repaired("discover", "SAME_ROOT_FIRST"); delay(200)
                            repaired("discover", "SAME_ROOT_REPEAT"); delay(200)
                            repaired("settings", "BACK_SETUP"); delay(200)
                            emit("FIXED_BACK_BEFORE", nav); nav.popBackStack(); delay(200)
                            emit("FIXED_BACK_SETTLED", nav, "discover")
                            summary = "Fixed matrix executed: inspect actual route/callback/markers. Rotation remains manual."
                        } catch (error: Exception) {
                            summary = "Runtime stopped: " + error.javaClass.simpleName
                            emit("FIXED_CASE_EXCEPTION", nav)
                        } finally { running = false }
                    }
                }
                Control("PREPARE_FIXED_RECREATE_AUX", !running) {
                    useFixed = true
                    repaired("library", "RECREATE_ROOT")
                    nav.getBackStackEntry("library").savedStateHandle["owned_probe"] = 17
                    repaired("settings", "RECREATE_AUX")
                    emit("FIXED_RECREATE_READY", nav, "settings")
                }
                Control("RECREATE_ACTIVITY", !running) { emit("RECREATE_BEFORE", nav); recreate() }
                BasicText("Current fixed route: " + fixed(entry?.destination?.route))
                NavHost(nav, startDestination = "discover", modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    for (route in routes) composable(route) { BasicText("Owned graph route: $route (no business content)") }
                }
                BasicText(summary)
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("qa_instance", instance); super.onSaveInstanceState(outState) }
}

@Composable
private fun Control(label: String, enabled: Boolean, action: () -> Unit) {
    BasicText(label, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = action).padding(8.dp))
}
