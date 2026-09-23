package com.orangestudio.neonplayer.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R

/**
 * The editable list of folders NeonPlayer searches.
 *
 * Shared by the first-run setup and the Settings tab so the two cannot drift apart: an empty slot
 * renders as "Open Directory", while a filled slot shows the folder's name plus a button to drop
 * it again.
 */
@Composable
fun FolderPicker(
    slots: List<Uri?>,
    onPickDirectory: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveDirectory: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slots.forEachIndexed { index, directory ->
            DirectorySlot(
                directory = directory,
                onPick = { onPickDirectory(index) },
                onRemove = { onRemoveDirectory(index) },
            )
        }

        FilledTonalButton(onClick = onAddSlot) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_directory))
        }
    }
}

/**
 * One directory button. An empty slot shows "Open Directory"; once a folder has been picked the
 * button shows its name and a button to drop it again appears next to it.
 */
@Composable
private fun DirectorySlot(
    directory: Uri?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = directory?.let(::directoryLabel) ?: stringResource(R.string.open_directory),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (directory != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.remove_directory),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Readable name for a granted tree, e.g. `content://.../tree/primary%3AMusic` becomes "Music".
 */
private fun directoryLabel(treeUri: Uri): String {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    return documentId?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
        ?: treeUri.lastPathSegment.orEmpty()
}
