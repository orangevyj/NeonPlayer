package com.orangestudio.neonplayer.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.orangestudio.neonplayer.R

/** Top-level destinations reachable from the bottom bar. */
enum class NeonPlayerTab { Music, Settings, Info }

/**
 * The app's bottom navigation: the music library, the settings that configure it, and the tab
 * that says who writes this app and under what licence it ships.
 *
 * A dark [neonPulse] - the effects switched off - renders the bar exactly like a stock
 * `NavigationBar`.
 */
@Composable
fun NeonPlayerBottomBar(
    selected: NeonPlayerTab,
    onSelect: (NeonPlayerTab) -> Unit,
    neonPulse: NeonPulse,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        NeonNavigationItem(
            selected = selected == NeonPlayerTab.Music,
            onClick = { onSelect(NeonPlayerTab.Music) },
            iconRes = R.drawable.ic_music_note,
            labelRes = R.string.music_tab,
            neonPulse = neonPulse,
        )

        NeonNavigationItem(
            selected = selected == NeonPlayerTab.Settings,
            onClick = { onSelect(NeonPlayerTab.Settings) },
            iconRes = R.drawable.ic_settings,
            labelRes = R.string.settings_tab,
            neonPulse = neonPulse,
        )

        NeonNavigationItem(
            selected = selected == NeonPlayerTab.Info,
            onClick = { onSelect(NeonPlayerTab.Info) },
            iconRes = R.drawable.ic_info,
            labelRes = R.string.info_tab,
            neonPulse = neonPulse,
        )
    }
}

/**
 * One bottom bar entry.
 *
 * Only the selected tab lights up. That keeps the pulsing from becoming noise, and makes the lit
 * tube double as the selection cue.
 */
@Composable
private fun RowScope.NeonNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    neonPulse: NeonPulse,
) {
    val pulse = if (selected) neonPulse else NeonPulse.Off

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            NeonIcon(
                painter = painterResource(iconRes),
                contentDescription = null,
                pulse = pulse,
            )
        },
        label = {
            NeonText(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                pulse = pulse,
            )
        },
    )
}
