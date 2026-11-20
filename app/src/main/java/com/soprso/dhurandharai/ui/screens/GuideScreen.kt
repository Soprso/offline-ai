package com.soprso.dhurandharai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private data class GuideSection(val number: String, val title: String, val body: String)

private val guideSections = listOf(
    GuideSection(
        "01",
        "HOW IT WORKS",
        "Offline AI loads a GGUF file into llama.cpp on this device. Prompts are tokenized and processed locally, " +
            "and replies come from the loaded model. Internet is used only to download model files from Hugging Face " +
            "and check their published checksums. No prompt, reply or conversation is sent to a server. " +
            "There is no account, profile or cloud inference.",
    ),
    GuideSection(
        "02",
        "DEVICE REQUIREMENTS",
        "This APK needs an arm64-v8a Android device. It is CPU-only; no GPU backend is compiled in. " +
            "RAM must hold model weights, the F16 K/V cache, native runtime, compute buffers and Android overhead at once. " +
            "Longer context and more KV heads increase KV cache. Keep headroom for Android and other apps. " +
            "The Models screen shows total and available RAM, Android version, ABI and free model storage; " +
            "REFRESH re-reads them before a load.",
    ),
    GuideSection(
        "03",
        "FINDING COMPATIBLE MODELS + LABELS",
        "The Models screen ranks entries against the current safe budget. " +
            "RECOMMENDED means the estimate fits with headroom and architecture/storage are verified. " +
            "SUPPORTED means it fits with limited headroom. " +
            "NOT RECOMMENDED means a verified failure such as ABI, storage or an estimate above budget. " +
            "UNKNOWN means metadata is missing or unverified; no confident guess is shown. " +
            "WEIGHTS use real GGUF tensor bytes when a file is installed, otherwise a published file-size upper bound. " +
            "KV is F16; changing CONTEXT recalculates it. " +
            "ARCHITECTURE, STORAGE and SPEED are separate checks; speed is not claimed without measurement.",
    ),
    GuideSection(
        "04",
        "DOWNLOAD + INSTALL",
        "Open an unlocked model and tap DOWNLOAD. Files are pinned to a Hugging Face revision and checked against " +
            "the published SHA-256 before becoming INSTALLED. Downloads can pause and resume when the server " +
            "supports HTTP Range. Gated models cannot be downloaded in this build because there is no sign-in; " +
            "accept the license on Hugging Face, then use + ADD to import the GGUF manually. " +
            "Manual imports use their own headers; unreadable metadata stays UNKNOWN.",
    ),
    GuideSection(
        "05",
        "LOAD + CHAT",
        "Open an installed model, choose CONTEXT, then tap LOAD. The app re-reads available RAM immediately: " +
            "it blocks with actual numbers if the current safe budget is exceeded and warns on memory pressure " +
            "or UNKNOWN metadata. One model is loaded at a time. In Chat, type a message and send it; " +
            "the native engine uses the model's own chat template when one is present. STOP cancels generation. " +
            "The displayed context is the exact value passed to the native loader.",
    ),
    GuideSection(
        "06",
        "UNLOAD + DELETE",
        "UNLOAD frees native memory and keeps the GGUF file on the device. " +
            "DELETE removes the file from app-private storage and the local registry; unload the model first " +
            "if it is currently loaded. Opening another model replaces the loaded one but does not delete files. " +
            "Your chat history remains local and is not affected by unloading or deleting a model file.",
    ),
    GuideSection(
        "07",
        "PERFORMANCE TIPS",
        "Performance is not measured or promised here. Smaller weights and shorter contexts lower estimated memory use; " +
            "KV cache scales with context, layer count and KV heads. Reduce CONTEXT when the estimate is close to " +
            "the safe budget, close other apps and tap REFRESH before loading. " +
            "Prompt length, thread count, heat and other apps all affect results, so measure on your own device. " +
            "UNKNOWN is a statement about missing data, not a benchmark result.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How to Use Offline AI", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "LOCAL-ONLY QUICK GUIDE · TAP A SECTION TO EXPAND",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            guideSections.forEachIndexed { index, section ->
                val isOpen = index in expanded
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = if (isOpen) expanded - index else expanded + index
                        }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${section.number} / ${section.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (isOpen) "[-]" else "[+]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (isOpen) {
                        Text(
                            section.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
