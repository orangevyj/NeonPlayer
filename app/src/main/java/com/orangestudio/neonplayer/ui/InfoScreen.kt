package com.orangestudio.neonplayer.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R
import java.time.LocalDate
import java.time.Month

/**
 * The Info tab: who writes this app, and the licence it is released under.
 *
 * The note at the top is the only part that ever changes - see [supportMessage] for the yearly
 * exception - and the rest is the GPL text exactly as it ships in `res/raw`, so the tab can never
 * drift away from the licence it claims to show.
 */
@Composable
fun InfoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    val licence = remember(context) { licenceText(context) }
    // Read once per visit rather than per frame: this screen has no reason to notice midnight.
    val today = remember { LocalDate.now() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.info_tab),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (version != null) {
            Text(
                text = stringResource(R.string.info_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        // The note gets a surface of its own so it reads as a message to the reader, rather than as
        // the first paragraph of the licence below it.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = stringResource(supportMessage(today)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.info_licence_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.info_licence_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = licence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.info_assets_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.info_assets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // One paragraph per credit, never joined into a single block, so each notice stays readable
        // and stays attached to the work it names.
        assetCredits.forEach { credit ->
            Text(
                text = stringResource(credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The bundled cover images' credits, in the order they were supplied.
 *
 * The order is deliberate - it is the order the credits were handed over, and it does not claim to
 * line up with the `image1..4` numbering, which follows a different pairing.
 */
private val assetCredits = listOf(
    R.string.info_assets_1,
    R.string.info_assets_2,
    R.string.info_assets_3,
    R.string.info_assets_4,
)

/**
 * The little note in the Info tab - except on the 1st of April, when the easter egg takes its place.
 *
 * Date is a parameter rather than read inside, so the switch can be tested without waiting a year,
 * and it is a [LocalDate] so the change happens at local midnight rather than at some UTC offset.
 */
internal fun supportMessage(date: LocalDate): Int =
    if (date.month == Month.APRIL && date.dayOfMonth == 1) {
        R.string.info_support_egg
    } else {
        R.string.info_support
    }

/** The bundled GPL text. A failure here costs the licence paragraphs, not the whole screen. */
private fun licenceText(context: Context): String = runCatching {
    context.resources.openRawResource(R.raw.gpl_3_0).bufferedReader().use { it.readText() }
}.getOrDefault("")

/** The installed version, or null if the platform will not say. */
private fun versionName(context: Context): String? = runCatching {
    val packages = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packages.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull()
