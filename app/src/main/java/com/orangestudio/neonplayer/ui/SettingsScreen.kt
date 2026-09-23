package com.orangestudio.neonplayer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R

/**
 * The Settings tab: the only place folders can be changed once setup has been completed, which is
 * what keeps the setup controls out of the everyday Music UI.
 */
@Composable
fun SettingsScreen(
    slots: List<Uri?>,
    hasPermission: Boolean,
    isScanning: Boolean,
    trackCount: Int,
    neonPulse: NeonPulse,
    accent: Color,
    onAccentChange: (Color) -> Unit,
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onNeonChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onPickDirectory: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveDirectory: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_tab),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_folders_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_folders_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FolderPicker(
            slots = slots,
            onPickDirectory = onPickDirectory,
            onAddSlot = onAddSlot,
            onRemoveDirectory = onRemoveDirectory,
        )

        ScanStatus(isScanning = isScanning, trackCount = trackCount)

        Spacer(Modifier.height(8.dp))

        NeonSwitchRow(pulse = neonPulse, onEnabledChange = onNeonChange)

        Spacer(Modifier.height(8.dp))

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

        if (!hasPermission) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_rationale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            FilledTonalButton(onClick = onRequestPermission) {
                Text(stringResource(R.string.grant_permission))
            }
        }
    }
}

/**
 * The on/off switch for the neon glow.
 *
 * The label samples the same [pulse], so the switch shows exactly what it controls, and its state
 * is the pulse's own - there is no second copy of "are the effects on?" to drift out of sync.
 */
@Composable
private fun NeonSwitchRow(pulse: NeonPulse, onEnabledChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            NeonText(
                text = stringResource(R.string.settings_neon_title),
                style = MaterialTheme.typography.titleMedium,
                pulse = pulse,
            )
            Text(
                text = stringResource(R.string.settings_neon_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(checked = pulse.on, onCheckedChange = onEnabledChange)
    }
}
