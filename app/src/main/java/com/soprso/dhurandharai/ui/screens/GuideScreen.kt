package com.soprso.dhurandharai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp

private sealed interface GuideBlock {
    data class Paragraph(val text: String) : GuideBlock
    data class Steps(val items: List<String>) : GuideBlock
    data class Bullets(val items: List<String>) : GuideBlock
    data class Note(val text: String) : GuideBlock
}

private data class GuideSection(val number: String, val title: String, val blocks: List<GuideBlock>)

private fun para(text: String) = GuideBlock.Paragraph(text)
private fun steps(vararg items: String) = GuideBlock.Steps(items.toList())
private fun bullets(vararg items: String) = GuideBlock.Bullets(items.toList())
private fun note(text: String) = GuideBlock.Note(text)

private val guideSections = listOf(
    GuideSection(
        "01",
        "WELCOME TO OFFLINE AI",
        listOf(
            para(
                "Offline AI is a chat app that runs a language model on your Android device. " +
                    "It does not send your messages to a cloud AI service.",
            ),
            para(
                "Inference uses llama.cpp compiled into the app. This build is CPU-only and " +
                    "runs on arm64-v8a devices. Prompts are tokenized and processed locally, " +
                    "and replies come from the GGUF model you load.",
            ),
            para(
                "Before you can chat, you need a compatible GGUF model file. Download one from " +
                    "the Model Library or import a .gguf file already on your device. " +
                    "Model downloads need an internet connection.",
            ),
            note(
                "Three different steps: downloading gets the GGUF file over the internet; " +
                    "installing/registering makes that file known to the app; loading puts the " +
                    "weights into memory so llama.cpp can generate a reply.",
            ),
            para(
                "Once a model is installed and loaded, conversations can be generated locally " +
                    "without a cloud AI service. Downloading models and opening the Privacy " +
                    "Policy link still need network access.",
            ),
        ),
    ),
    GuideSection(
        "02",
        "UNDERSTANDING THE APP INTERFACE",
        listOf(
            para(
                "Main chat screen — titled Offline AI. The line under the title shows " +
                    "loaded · <model name> or no model loaded; this is the current model indicator. " +
                    "In Models, the ENGINE row also shows loaded · ... and context N.",
            ),
            para(
                "Navigation drawer — tap ≡ at the top left for New conversation, History, " +
                    "Model library and Settings. New conversation starts a fresh chat and keeps " +
                    "the old one in History.",
            ),
            para(
                "History — saved conversations are listed by title. Tap one to open it, " +
                    "long-press to rename it, or tap ✕ to delete that conversation.",
            ),
            para(
                "Model Library — the drawer item Model library opens the Models screen. " +
                    "When no model is loaded, the chat screen also shows a MODELS → shortcut.",
            ),
            para(
                "Message composer — type in the Message… field and tap ▶ (or your keyboard's " +
                    "Send key) to send. While generating, the button becomes ■ STOP and the top " +
                    "bar shows generating Ns. Replies stream in token by token.",
            ),
            para(
                "Chat actions — long-press a message to reveal COPY; on AI replies, REPORT " +
                    "also appears. Reports are saved on this device only. Errors appear above " +
                    "the composer with a ✕ to dismiss them. Debug builds also show a measured " +
                    "performance line; release builds do not.",
            ),
        ),
    ),
    GuideSection(
        "03",
        "HOW TO OPEN THE MODEL LIBRARY",
        listOf(
            steps(
                "Start on the main chat screen (Offline AI).",
                "Tap ≡ at the top left to open the drawer, then tap Model library (◈). " +
                    "The screen title is Models. You can also tap MODELS → in the chat banner " +
                    "when no model is loaded.",
                "The catalogue has two groups: RECOMMENDED (models that fit the safe memory " +
                    "budget with headroom and are not installed) and AVAILABLE MODELS (all " +
                    "catalogue entries plus imported GGUF files). ★ PICK marks a recommended model.",
                "Use each row's status to tell them apart: AVAILABLE means not on the device; " +
                    "DOWNLOADING, VERIFYING, ERROR, AVAILABLE · paused and AVAILABLE · gated are " +
                    "other states; INSTALLED means the file is in app-private storage; LOADED " +
                    "means it is active in memory.",
                "Tap a row to open its details sheet. It shows parameters, architecture, " +
                    "quantization, download size, license, source/revision, context choices, " +
                    "memory breakdown and compatibility. This is where you inspect a model " +
                    "before downloading it.",
            ),
        ),
    ),
    GuideSection(
        "04",
        "GGUF, SIZES AND QUANTISATION",
        listOf(
            para(
                "A GGUF model is a file format used by llama.cpp. It holds the model weights " +
                    "and a header with metadata such as architecture, layer count and context " +
                    "length. Only .gguf files can be imported with + ADD.",
            ),
            para(
                "Parameter size (0.5B, 1.1B, 1.5B, 3B) roughly means how many weights the " +
                    "model has: 0.5B is about 500 million and 3B is about 3 billion. It is a " +
                    "size and capability hint, not a promise of answer quality or speed. The " +
                    "catalogue also has smaller 135M/360M and larger 3.8B entries.",
            ),
            para(
                "Quantisation stores weights with fewer bits. Q4 uses about 4 bits per weight, " +
                    "Q5 about 5 and Q8 about 8; this catalogue uses Q3_K_M, Q4_K_M, Q4_0, Q4 " +
                    "and Q5_K_M. Lower-bit quantisations usually make the file smaller and use " +
                    "less memory for weights; higher-bit ones keep more detail but need more space.",
            ),
            para(
                "A smaller or more heavily quantised model usually needs less RAM. That does " +
                    "not guarantee it fits, because the K/V cache, runtime and compute buffers " +
                    "also count.",
            ),
            note(
                "A larger model is not automatically the best choice for a phone. CPU-only " +
                    "inference, limited RAM and heat matter. The app does not measure or promise " +
                    "tokens per second; the details sheet always labels SPEED (NOT MEASURED).",
            ),
        ),
    ),
    GuideSection(
        "05",
        "COMPATIBILITY AND MEMORY BUDGET",
        listOf(
            para(
                "The estimator adds WEIGHTS (real GGUF tensor bytes when installed, otherwise " +
                    "the published file size as an upper bound), a F16 K/V cache, runtime " +
                    "reserve, compute buffers and a 25% safety margin. That total is EST. PEAK; " +
                    "the phone's limit is SAFE BUDGET.",
            ),
            para(
                "Safe budget is conservative: no more than 45% of total RAM or 80% of currently " +
                    "available RAM, and less under memory pressure. Longer context, more layers " +
                    "and more KV heads increase the K/V cache; sliding-window layouts use their " +
                    "real window where the architecture is known.",
            ),
            bullets(
                "RECOMMENDED — the estimate fits with headroom (at most 80% of the safe " +
                    "budget), architecture and storage checks pass, and metadata is known. " +
                    "These rows show ★ PICK.",
                "SUPPORTED — the estimate fits the safe budget but with limited headroom; " +
                    "architecture and storage still pass. Review EST. PEAK and consider a " +
                    "shorter context.",
                "NOT RECOMMENDED — a verified problem: wrong ABI, not enough free storage, " +
                    "or an estimate above the safe budget. DOWNLOAD is disabled and LOAD is blocked.",
                "UNKNOWN — the app cannot verify enough metadata, or memory is unknown under " +
                    "pressure. It is not a speed score or a claim that the model works.",
            ),
            para(
                "This app has no separate INCOMPATIBLE label. Verified incompatibilities appear " +
                    "as NOT RECOMMENDED with a FAIL check and a reason; when the app cannot " +
                    "verify, the status stays UNKNOWN.",
            ),
            para(
                "Available RAM changes while other apps run or Android reclaims cached pages. " +
                    "Tap REFRESH in Models to re-read the device, and note that LOAD reads " +
                    "available RAM again. Never judge a model by parameter count or download " +
                    "size alone — read EST. PEAK, SAFE BUDGET and the COMPATIBILITY reason.",
            ),
            note(
                "Beginner recommendation: 1) read the DEVICE / RAM / STORAGE lines and tap " +
                    "REFRESH; 2) prefer RECOMMENDED; 3) try SUPPORTED only after reviewing its " +
                    "memory estimate; 4) avoid NOT RECOMMENDED and UNKNOWN unless the app's " +
                    "reason explains a safe next step, such as a shorter context, more free " +
                    "space or importing the correct GGUF.",
            ),
        ),
    ),
    GuideSection(
        "06",
        "DOWNLOAD AND INSTALL A MODEL",
        listOf(
            steps(
                "Open Models from the drawer (or MODELS → in the chat banner).",
                "Tap a model row to open its details sheet.",
                "Review PARAMETERS, ARCHITECTURE, QUANTIZATION, DOWNLOAD SIZE, LICENSE, " +
                    "SOURCE/REVISION, CONTEXT, MEMORY BREAKDOWN (EST. PEAK vs SAFE BUDGET) " +
                    "and COMPATIBILITY. Gated entries show LICENSE REQUIRED.",
                "Tap DOWNLOAD. Direct downloads are disabled for gated models and for NOT " +
                    "RECOMMENDED models. For gated models, accept the license on Hugging Face, " +
                    "then use + ADD to import the GGUF manually.",
                "The row shows DOWNLOADING with a progress bar, percent, bytes done / total " +
                    "and an estimated speed. The sheet offers PAUSE and CANCEL. Pausing leaves " +
                    "AVAILABLE · paused with RESUME / CANCEL; resume uses an HTTP Range request " +
                    "when the server supports it. Only one download runs at a time.",
                "When the bytes finish, the row changes to VERIFYING. The app checks the file " +
                    "size and compares its SHA-256 with the published catalogue checksum. A " +
                    "mismatch deletes the partial file and shows ERROR with RETRY / CANCEL.",
                "After verification the file is moved out of its .part file into app-private " +
                    "storage and written to the local registry with its metadata. That " +
                    "automatic step is installation/registration — there is no separate " +
                    "INSTALL button for catalogue downloads. A manual import with + ADD copies " +
                    "a .gguf, computes its own SHA-256 and registers it, but has no publisher " +
                    "checksum to compare against.",
                "The same row now reads INSTALLED and appears among AVAILABLE MODELS; there " +
                    "is no separate Downloads tab. Its details sheet shows FILE, CHECKSUM, " +
                    "INSTALLED and LAST USED.",
            ),
            note(
                "Downloading = fetching the file. Installing/registering = adding it to the " +
                    "app's local list. Loading = preparing it in memory for inference. Loading " +
                    "happens later, with LOAD.",
            ),
        ),
    ),
    GuideSection(
        "07",
        "LOAD A MODEL AND START CHATTING",
        listOf(
            steps(
                "In Models, find the row marked INSTALLED (imported files appear here too). " +
                    "Tap it to open the details sheet.",
                "Choose a CONTEXT value from the offered choices (for example 512, 1024, 2048, " +
                    "4096 or 8192, limited by the model). Changing it recalculates the K/V " +
                    "cache, EST. PEAK and compatibility; the same value is passed to the native loader.",
                "Tap LOAD. If another model is active, a 'Replace loaded model?' dialog appears: " +
                    "REPLACE unloads it (the file stays on disk) and loads your selection; " +
                    "CANCEL keeps the current model.",
                "While loading, ENGINE shows loading <file> and the status line shows Loading " +
                    "<name> · context N, plus warnings when memory, K/V layout or metadata are " +
                    "unverified. Wait until ENGINE reads loaded · <name> with context N.",
                "If loading is blocked for insufficient memory, read the red status message " +
                    "with the actual numbers. Close other apps, tap REFRESH and try a shorter " +
                    "context or a smaller model. If the file is missing, download or import it " +
                    "again; if it is corrupt or incompatible, delete it and choose another model.",
                "The active model is shown in the chat top bar as loaded · <name>, or no model " +
                    "loaded when none is active.",
                "Tap ← to return to chat, type in Message… and tap ▶ or your keyboard's Send " +
                    "key. The reply streams in as it is generated; ■ STOP cancels and keeps the " +
                    "partial text.",
                "Response speed depends on the model, its quantisation, the chosen context " +
                    "length and your device hardware (CPU-only). No particular speed is promised.",
            ),
            para(
                "Switching models: only one model is loaded at a time. Loading another unloads " +
                    "the current one after the REPLACE confirmation; you can also use UNLOAD in " +
                    "the ENGINE row or the details sheet first. Model changes are not allowed " +
                    "while generation is running.",
            ),
        ),
    ),
    GuideSection(
        "08",
        "MANAGING MODELS",
        listOf(
            bullets(
                "View — the Models screen lists catalogue entries and imported GGUFs with " +
                    "status labels. The details sheet shows FILE, CHECKSUM, INSTALLED and LAST " +
                    "USED; DEVICE shows free model storage.",
                "Load — tap an INSTALLED row, choose CONTEXT and tap LOAD. A LOADED row offers " +
                    "UNLOAD instead.",
                "Unload — UNLOAD in the ENGINE row or details sheet frees native memory and " +
                    "keeps the GGUF file on the device.",
                "Switch — tap LOAD on a different installed model and confirm REPLACE. The " +
                    "previous model is unloaded but its file is kept.",
                "Delete — an INSTALLED row offers DELETE with a confirmation. Unload first if " +
                    "it is LOADED. This removes the GGUF file from app-private storage and its " +
                    "local registry entry; it cannot be undone.",
                "Storage — DOWNLOAD SIZE is per file; the DEVICE line shows model-dir and " +
                    "app-data space free. Downloads need roughly the file size plus 128 MiB " +
                    "of headroom; an installed model needs about 16 MiB of working space.",
            ),
            para(
                "Deleting a model file is not the same as deleting a conversation. Chats stay " +
                    "in History; delete them there with a long-press and ✕ if you want them " +
                    "gone. An imported file with the same SHA-256 as an installed model is " +
                    "rejected as a duplicate.",
            ),
        ),
    ),
    GuideSection(
        "09",
        "CONTEXT LENGTH AND MEMORY",
        listOf(
            para(
                "Context length is how many tokens — pieces of words — the model can consider " +
                    "at once. It covers your latest message, earlier messages in the " +
                    "conversation and room for the reply. You choose it under CONTEXT in a " +
                    "model's details sheet; the loaded value appears as context N in Models.",
            ),
            para(
                "Longer context uses more memory because the F16 K/V cache grows with the " +
                    "number of positions, layers and KV heads. It can push EST. PEAK above " +
                    "SAFE BUDGET, so compatibility changes when CONTEXT changes. Shorter " +
                    "contexts lower that estimate.",
            ),
            para(
                "The app does not automatically remove or truncate older messages. It passes " +
                    "the whole stored conversation to the native engine. If the prompt plus " +
                    "the reply budget (the app requests up to 512 new tokens) exceeds the " +
                    "context, generation fails with a " +
                    "context-length error and the app says the conversation is too long: " +
                    "shorten the message or start a new chat from the drawer. Old conversations " +
                    "stay in History.",
            ),
            para(
                "A model can fit at one moment and fail later because available RAM changes. " +
                    "Android reclaims cached memory and other apps may use it. The load action " +
                    "reads RAM again, and the RAM line shows PRESSURE in red when memory is " +
                    "tight. Closing other memory-intensive apps and tapping REFRESH before " +
                    "LOAD can help. The app exposes no thread or GPU settings to tune here.",
            ),
        ),
    ),
    GuideSection(
        "10",
        "TROUBLESHOOTING",
        listOf(
            bullets(
                "Download interrupted or failed — check your connection, then open the model " +
                    "row and tap RESUME (or RETRY). CANCEL clears the partial file. Interrupted " +
                    "downloads come back as AVAILABLE · paused after a restart.",
                "Insufficient storage — free space or delete an unused model first. The STORAGE " +
                    "CHECK explains how much is needed; downloads need about the file size " +
                    "plus 128 MiB of headroom.",
                "Verification failure — a 'Checksum mismatch' or size mismatch means the " +
                    "partial file was deleted. Tap RETRY over a reliable connection. Manually " +
                    "imported files have no publisher checksum; make sure the .gguf is complete " +
                    "and correct.",
                "Model incompatible with the runtime — check COMPATIBILITY and ARCHITECTURE " +
                    "CHECK. This build is arm64-v8a and CPU-only; choose a model whose " +
                    "architecture is mapped and whose status is RECOMMENDED or SUPPORTED. " +
                    "Gated entries need license acceptance outside the app, then + ADD.",
                "Insufficient available RAM — close other apps, tap REFRESH and try again. " +
                    "Prefer RECOMMENDED, a smaller model or a shorter CONTEXT. A red PRESSURE " +
                    "label means memory is tight.",
                "Model loading failure — read the red status line: stop generation before " +
                    "switching; for 'Model file is missing', download or import it again; for " +
                    "context errors, choose a shorter context; if it stays UNKNOWN or fails " +
                    "repeatedly, UNLOAD and reload or delete it and pick another model.",
                "Slow responses — nothing is benchmarked in this app. Smaller weights, lower " +
                    "quantisation, shorter context, a cooler device and fewer background apps " +
                    "can help; performance still depends on the hardware.",
                "Generation failure or context exhaustion — retry the message. If it says the " +
                    "conversation is too long, shorten it or start a new chat with the drawer's " +
                    "New conversation. Repeated failures can be helped by UNLOAD then LOAD again.",
                "Model not appearing in the installed list — check its status first: " +
                    "DOWNLOADING, VERIFYING, ERROR, AVAILABLE · paused and gated entries are " +
                    "not installed yet. Finish or retry the download, or import a valid .gguf " +
                    "with + ADD. Installed models appear on the Models screen (there is no " +
                    "separate downloaded tab); duplicate SHA-256 imports are rejected.",
            ),
        ),
    ),
    GuideSection(
        "11",
        "PRIVACY AND OFFLINE BEHAVIOUR",
        listOf(
            para(
                "Chat inference takes place locally on this device with llama.cpp. Prompts, " +
                    "replies and conversations are not sent to a cloud AI service for " +
                    "generation. There is no account, telemetry, analytics or tracking in " +
                    "this build.",
            ),
            para(
                "Model downloads need internet access. Catalogue files are fetched over " +
                    "HTTPS from configured Hugging Face repository URLs pinned to a revision. " +
                    "No chat text is included in download requests. Normal HTTPS connection " +
                    "information, such as your IP address, is visible to the host and network " +
                    "as with any website.",
            ),
            para(
                "AI response reporting is optional and user-initiated. If you explicitly " +
                    "submit a report, only the report content shown to you (reported AI " +
                    "response/excerpt, reason, optional details, and limited technical " +
                    "metadata such as model/app/reference) is sent over HTTPS to Shadow Studio " +
                    "Labs for safety review. Reports are not submitted automatically and " +
                    "entire conversations are not uploaded automatically.",
            ),
            para(
                "Conversations are stored locally in the app's Room database and are not " +
                    "synced. Android backups are disabled for this app (allowBackup=false). " +
                    "Data is protected by the Android app sandbox but is not additionally " +
                    "encrypted at rest.",
            ),
            para(
                "Deleting a conversation in History permanently removes it and its messages. " +
                    "Deleting a model removes only its GGUF file and registry entry. Chats " +
                    "remain until you delete them, clear the app's data or uninstall the app; " +
                    "there is no automatic cloud or local retention timer.",
            ),
            para("Read the full Privacy Policy from Settings → Privacy Policy."),
        ),
    ),
    GuideSection(
        "12",
        "SAFETY AND REPORTING",
        listOf(
            para(
                "Prompts and generated replies are screened on this device before and during " +
                    "generation. A blocked prompt is not sent to the model; a blocked reply is " +
                    "stopped, not shown and replaced with a refusal. These are conservative " +
                    "local checks and are not a guarantee that every unsafe output is caught.",
            ),
            para(
                "To flag an AI reply, long-press that AI message and tap REPORT. Choose a " +
                    "reason, add optional details, review the content preview and disclosure, " +
                    "then tap SUBMIT REPORT only if you want to send it to Shadow Studio Labs " +
                    "for safety review. Nothing is submitted automatically.",
            ),
            para(
                "When submission is confirmed by the server, the app shows a report " +
                    "reference. If submission cannot be completed (offline/backend issue), " +
                    "the report is saved locally as pending/failed, is not marked submitted, " +
                    "and can be retried manually from Settings → Safety & Reports. Local " +
                    "records remain on this device; submitted reports are handled as described " +
                    "in the Privacy Policy.",
            ),
        ),
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
                "LOCAL MODEL GUIDE · TAP A SECTION TO EXPAND",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                "QUICK START",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            steps(
                "Open Models → choose a model → download → install/register → load → start chatting.",
                "Internet is needed to download a model. Chat replies are generated on this device.",
            ).let { quickStart ->
                GuideBlocks(blocks = listOf(quickStart))
            }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${section.number} / ${section.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (isOpen) "[-]" else "[+]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                    }
                    if (isOpen) {
                        GuideBlocks(blocks = section.blocks)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun GuideBlocks(blocks: List<GuideBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> GuideBlockView(block) }
    }
}

@Composable
private fun GuideBlockView(block: GuideBlock) {
    when (block) {
        is GuideBlock.Paragraph -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is GuideBlock.Steps -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is GuideBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is GuideBlock.Note -> Text(
            "> ${block.text}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
