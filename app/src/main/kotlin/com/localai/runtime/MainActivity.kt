package com.localai.runtime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.DarkMode
import com.localai.runtime.navigation.Routes
import com.localai.runtime.navigation.topDestinations
import com.localai.runtime.ui.rememberWindowWidthSizeClass
import com.localai.runtime.ui.screens.about.AboutScreen
import com.localai.runtime.ui.screens.apidocs.ApiDocsScreen
import com.localai.runtime.ui.screens.benchmark.BenchmarkScreen
import com.localai.runtime.ui.screens.chat.ChatScreen
import com.localai.runtime.ui.screens.devices.DevicesScreen
import com.localai.runtime.ui.screens.downloads.DownloadsScreen
import com.localai.runtime.ui.screens.home.HomeScreen
import com.localai.runtime.ui.screens.logs.LogsScreen
import com.localai.runtime.ui.screens.models.ModelsScreen
import com.localai.runtime.ui.screens.modeldetail.ModelDetailScreen
import com.localai.runtime.ui.screens.monitoring.MonitoringScreen
import com.localai.runtime.ui.screens.server.ApiServerScreen
import com.localai.runtime.ui.screens.settings.SettingsScreen
import com.localai.runtime.ui.screens.storage.StorageScreen
import com.localai.runtime.ui.theme.LocalAiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LocalAiApplication).container
        setContent {
            val settings by container.settingsRepository.flow
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            LocalAiTheme(darkMode = settings.darkMode, dynamicColor = settings.dynamicColor) {
                RootLayout(container)
            }
        }
    }
}

@Composable
private fun RootLayout(container: com.localai.runtime.runtime.AppContainer) {
    val navController = rememberNavController()
    val widthClass = rememberWindowWidthSizeClass()
    val showRail = widthClass >= WindowWidthSizeClass.Medium

    if (showRail) {
        Row(Modifier.fillMaxSize()) {
            Rail(navController)
            AppNavHost(navController, container, Modifier.fillMaxSize())
        }
    } else {
        Scaffold(
            bottomBar = { BottomBar(navController) },
        ) { padding ->
            AppNavHost(navController, container, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    container: com.localai.runtime.runtime.AppContainer,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) { HomeScreen(container, navController) }
        composable(Routes.MODELS) { ModelsScreen(container, navController) }
        composable(Routes.CHAT) { ChatScreen(container) }
        composable(Routes.SERVER) { ApiServerScreen(container, navController) }
        composable(Routes.SETTINGS) { SettingsScreen(container, navController) }
        composable(Routes.MODEL_DETAIL) { entry ->
            val modelId = entry.arguments?.getString("modelId").orEmpty()
            ModelDetailScreen(container, navController, modelId)
        }
        composable(Routes.DOWNLOADS) { DownloadsScreen(container, navController) }
        composable(Routes.DEVICES) { DevicesScreen(container, navController) }
        composable(Routes.MONITORING) { MonitoringScreen(container, navController) }
        composable(Routes.LOGS) { LogsScreen(container, navController) }
        composable(Routes.STORAGE) { StorageScreen(container, navController) }
        composable(Routes.BENCHMARK) { BenchmarkScreen(container, navController) }
        composable(Routes.API_DOCS) { ApiDocsScreen(container, navController) }
        composable(Routes.ABOUT) { AboutScreen(container, navController) }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    NavigationBar {
        topDestinations.forEach { dest ->
            NavigationBarItem(
                selected = currentRoute == dest.route,
                onClick = {
                    navController.navigate(dest.route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}

@Composable
private fun Rail(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    NavigationRail {
        topDestinations.forEach { dest ->
            NavigationRailItem(
                selected = currentRoute == dest.route,
                onClick = {
                    navController.navigate(dest.route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}
