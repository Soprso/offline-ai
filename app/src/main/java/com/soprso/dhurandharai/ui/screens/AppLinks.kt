package com.soprso.dhurandharai.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Public links used by the app. Kept in one place so the URL cannot drift. */
object AppLinks {
    const val PRIVACY_POLICY_URL = "https://shadow-studio-labs.netlify.app/privacy/offline-ai"
}

/**
 * Opens [url] in the device's default browser.
 *
 * Returns false when no installed browser can handle ACTION_VIEW (or the launch
 * is blocked), so callers can show a message instead of crashing.
 */
fun openUrlInBrowser(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Shared terminal-styled row for opening the Privacy Policy in a browser. */
@Composable
fun PrivacyPolicyLink(label: String = "PRIVACY POLICY  →") {
    val context = LocalContext.current
    var browserUnavailable by remember { mutableStateOf(false) }

    TextButton(
        onClick = {
            browserUnavailable = !openUrlInBrowser(context, AppLinks.PRIVACY_POLICY_URL)
        },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }

    if (browserUnavailable) {
        AlertDialog(
            onDismissRequest = { browserUnavailable = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Browser unavailable", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "No installed browser can open the Privacy Policy. Install a browser and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { browserUnavailable = false }) {
                    Text("OK", style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
}
