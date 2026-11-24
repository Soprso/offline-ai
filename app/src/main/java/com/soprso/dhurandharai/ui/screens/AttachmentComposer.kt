package com.soprso.dhurandharai.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soprso.dhurandharai.document.AttachmentMime

@Composable
fun AttachmentComposer(
    uris: List<Uri>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (uris.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(uris, key = { _, uri -> uri.toString() }) { index, uri ->
                    AttachmentPreviewChip(
                        uri = uri,
                        index = index,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreviewChip(
    uri: Uri,
    index: Int,
    onRemove: (Int) -> Unit,
) {
    val context = LocalContext.current
    val fileName = uri.lastPathSegment ?: "file"
    val mime = uriToMime(uri)?.lowercase() ?: ""
    val isImage = mime.endsWith("jpeg") || mime.endsWith("jpg") || mime.endsWith("png") || mime.endsWith("webp")

    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .size(width = 110.dp, height = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (isImage) {
            Text(
                text = "IMG",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = mime.takeIf { it.isNotEmpty() }?.uppercase()?.firstOrNull()?.toString() ?: "DOC",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        IconButton(onClick = { onRemove(index) }) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun uriToMime(uri: Uri): String? {
    val path = uri.path ?: ""
    return when {
        path.endsWith(".pdf", ignoreCase = true) -> AttachmentMime.PDF
        path.endsWith(".docx", ignoreCase = true) -> AttachmentMime.DOCX
        path.endsWith(".pptx", ignoreCase = true) -> AttachmentMime.PPTX
        path.endsWith(".txt", ignoreCase = true) -> AttachmentMime.TXT
        path.endsWith(".md", ignoreCase = true) -> AttachmentMime.MARKDOWN
        path.endsWith(".csv", ignoreCase = true) -> AttachmentMime.CSV
        path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> AttachmentMime.JPEG
        path.endsWith(".png", ignoreCase = true) -> AttachmentMime.PNG
        path.endsWith(".webp", ignoreCase = true) -> AttachmentMime.WEBP
        else -> null
    }
}
