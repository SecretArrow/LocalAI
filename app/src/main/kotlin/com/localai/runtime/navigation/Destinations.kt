package com.localai.runtime.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.graphics.vector.ImageVector

/** Route names shared by the nav host and view models. */
object Routes {
    const val HOME = "home"
    const val MODELS = "models"
    const val CHAT = "chat"
    const val SERVER = "server"
    const val SETTINGS = "settings"
    const val MODEL_DETAIL = "model/{modelId}"
    const val DOWNLOADS = "downloads"
    const val DEVICES = "devices"
    const val MONITORING = "monitoring"
    const val LOGS = "logs"
    const val STORAGE = "storage"
    const val BENCHMARK = "benchmark"
    const val API_DOCS = "api_docs"
    const val ABOUT = "about"

    fun modelDetail(modelId: String) = "model/$modelId"
}

/** Bottom navigation destinations (phones) / navigation rail (tablets). */
data class TopDestination(val route: String, val label: String, val icon: ImageVector)

val topDestinations = listOf(
    TopDestination(Routes.HOME, "Home", Icons.Filled.Home),
    TopDestination(Routes.MODELS, "Models", Icons.Filled.Memory),
    TopDestination(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    TopDestination(Routes.SERVER, "Server", Icons.Filled.Dns),
    TopDestination(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)
