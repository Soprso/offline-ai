package com.soprso.dhurandharai.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Reads the app version name once, for display in Settings. */
@Composable
fun rememberAppVersion(context: Context): String = remember {
    val pkg = context.packageName
    runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    }.getOrDefault("?")
}
