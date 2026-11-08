package com.familyguard.parent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.familyguard.parent.data.Session
import com.familyguard.parent.data.Store
import com.familyguard.parent.ui.AlertsScreen
import com.familyguard.parent.ui.AppsScreen
import com.familyguard.parent.ui.DashboardScreen
import com.familyguard.parent.ui.GeofenceScreen
import com.familyguard.parent.ui.LocationScreen
import com.familyguard.parent.ui.LoginScreen
import com.familyguard.parent.ui.MirrorScreen
import com.familyguard.parent.ui.MoreScreen
import com.familyguard.parent.ui.RemoteScreen
import com.familyguard.parent.ui.ScreenTimeScreen
import com.familyguard.parent.ui.theme.FamilyGuardTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifPermissionIfNeeded()
        setContent {
            FamilyGuardTheme {
                val vm: MainViewModel = viewModel()
                Root(vm)
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        if (Store.token.isNotEmpty()) {
            ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java))
        }
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val context = LocalContext.current
    var auth by remember { mutableStateOf(Store.token.isNotEmpty()) }
    var notice by remember { mutableStateOf("") }

    // Backend rejected the saved token (401 / WS 4001): show login once, with reason.
    LaunchedEffect(Unit) {
        Session.expired.collect { reason ->
            notice = reason
            auth = false
        }
    }

    if (!auth) {
        LoginScreen(notice = notice, onLoggedIn = {
            notice = ""
            auth = true
            vm.startSession()
            ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
        })
    } else {
        AppScaffold(vm = vm, onLoggedOut = { auth = false })
    }
}

private data class TabItem(val route: String, val icon: ImageVector, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: MainViewModel, onLoggedOut: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val tabs = listOf(
        TabItem("home", Icons.Outlined.Home, "Home"),
        TabItem("time", Icons.Outlined.Schedule, "Time"),
        TabItem("apps", Icons.Outlined.Apps, "Apps"),
        TabItem("map", Icons.Outlined.Place, "Map"),
        TabItem("alerts", Icons.Outlined.Notifications, "Alerts")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamilyGuard") },
                actions = {
                    IconButton(onClick = { nav.navigateSingleTop("more") }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { DashboardScreen(vm, nav) }
            composable("time") { ScreenTimeScreen(vm) }
            composable("apps") { AppsScreen(vm) }
            composable("map") { LocationScreen(vm) }
            composable("alerts") { AlertsScreen(vm) }
            composable("mirror") { MirrorScreen(vm) }
            composable("remote") { RemoteScreen(vm) }
            composable("geofence") { GeofenceScreen(vm) }
            composable("more") { MoreScreen(vm, onLoggedOut) }
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}
