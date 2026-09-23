package com.orangestudio.neonplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R
import com.orangestudio.neonplayer.data.CoverArt
import com.orangestudio.neonplayer.data.MusicTrack

private val CoverSize = 56.dp
private val ScreenPadding = 16.dp
private val CoverSpacing = 12.dp

/**
 * The Music tab: a neon "Music" sign, then what was found in the folders picked during setup.
 *
 * The folder and permission controls deliberately are not here - they live in [SetupScreen] for the
 * first run and in [SettingsScreen] afterwards - so this stays the everyday library view.
 *
 * Tapping a row opens the player at that track; [onTrackClick] receives its position in [tracks],
 * which is what the queue rules in the data layer work in.
 */
@Composable
fun MusicScreen(
    tracks: List<MusicTrack>,
    isScanning: Boolean,
    hasDirectories: Boolean,
    neonPulse: NeonPulse,
    onTrackClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "title") {
            NeonText(
                text = stringResource(R.string.music_tab),
                style = MaterialTheme.typography.displaySmall,
                pulse = neonPulse,
                modifier = Modifier.padding(
                    start = ScreenPadding,
                    end = ScreenPadding,
                    top = 24.dp,
                    bottom = 4.dp,
                ),
            )
        }

        item(key = "status") {
            ScanStatus(isScanning = isScanning, trackCount = tracks.size)
        }

        if (tracks.isEmpty()) {
            item(key = "empty") {
                EmptyMessage(hasDirectories = hasDirectories, isScanning = isScanning)
            }
        } else {
            itemsIndexed(
                items = tracks,
                key = { _, track -> track.uri.toString() },
            ) { index, track ->
                TrackRow(track = track, onClick = { onTrackClick(index) })
                HorizontalDivider(
                    modifier = Modifier.padding(
                        start = ScreenPadding + CoverSize + CoverSpacing,
                        end = ScreenPadding,
                    ),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** Progress while scanning, then a short summary of what the scan found. */
@Composable
fun ScanStatus(isScanning: Boolean, trackCount: Int, modifier: Modifier = Modifier) {
    when {
        isScanning -> Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        trackCount > 0 -> Text(
            text = pluralStringResource(R.plurals.song_count, trackCount, trackCount),
            modifier = modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyMessage(hasDirectories: Boolean, isScanning: Boolean) {
    if (isScanning) return

    Text(
        text = stringResource(
            if (hasDirectories) R.string.no_music_found else R.string.no_directories
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TrackRow(track: MusicTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(track = track)
        Spacer(Modifier.width(CoverSpacing))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist ?: stringResource(R.string.unknown_artist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The file's own cover art when it has one, otherwise one of the four bundled images. */
@Composable
private fun CoverThumbnail(track: MusicTrack) {
    val artwork = remember(track.uri, track.coverArt) {
        track.coverArt?.let(CoverArt::decode)?.asImageBitmap()
    }
    val thumbnailModifier = Modifier
        .size(CoverSize)
        .clip(RoundedCornerShape(8.dp))

    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = thumbnailModifier,
        )
    } else {
        Image(
            painter = painterResource(CoverArt.placeholderFor(track.fileName)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = thumbnailModifier,
        )
    }
}
