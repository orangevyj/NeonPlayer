package com.orangestudio.neonplayer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R

/**
 * First-run setup, shown until the user has finished it once.
 *
 * It asks for the audio permission and requires at least one music folder before letting the user
 * through, so the Music tab always has something to display the moment setup ends. Once
 * [onContinue] is called the app remembers it and this screen is never shown again - the folders
 * stay reachable from Settings.
 */
@Composable
fun SetupScreen(
    slots: List<Uri?>,
    hasPermission: Boolean,
    accent: Color,
    onAccentChange: (Color) -> Unit,
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onRequestPermission: () -> Unit,
    onPickDirectory: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveDirectory: (Int) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFolder = slots.any { it != null }

    // Ask once, the first time setup appears, so the permission prompt belongs to onboarding
    // rather than to the everyday UI.
    LaunchedEffect(Unit) {
        if (!hasPermission) onRequestPermission()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )

            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (!hasPermission) {
                Text(
                    text = stringResource(R.string.permission_rationale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(onClick = onRequestPermission) {
                    Text(stringResource(R.string.grant_permission))
                }
            }

            FolderPicker(
                slots = slots,
                onPickDirectory = onPickDirectory,
                onAddSlot = onAddSlot,
                onRemoveDirectory = onRemoveDirectory,
            )

            NeonColorPicker(accent = accent, onAccentChange = onAccentChange)

            NeonIntensitySlider(
                intensity = intensity,
                accent = accent,
                onIntensityChange = onIntensityChange,
            )

            NeonSensitivitySlider(
                sensitivity = sensitivity,
                accent = accent,
                onSensitivityChange = onSensitivityChange,
            )
        }

        Button(
            onClick = onContinue,
            enabled = hasFolder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_continue))
        }

        if (!hasFolder) {
            Text(
                text = stringResource(R.string.setup_need_folder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
