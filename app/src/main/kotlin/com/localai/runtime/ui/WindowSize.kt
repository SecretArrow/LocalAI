package com.localai.runtime.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Returns the current window width size class for adaptive navigation. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWindowWidthSizeClass(): WindowWidthSizeClass {
    val context = LocalContext.current
    val windowClass = calculateWindowSizeClass(context as androidx.activity.ComponentActivity)
    return windowClass.widthSizeClass
}
