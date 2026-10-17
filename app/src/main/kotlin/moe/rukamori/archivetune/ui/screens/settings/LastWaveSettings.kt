/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LastWave24BitAudioEnabledKey
import moe.rukamori.archivetune.constants.LastWaveDolbyAtmosEnabledKey
import moe.rukamori.archivetune.constants.LastWaveHiResFlacEnabledKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastWaveSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val (hiResFlacEnabled, onHiResFlacChange) = rememberPreference(LastWaveHiResFlacEnabledKey, false)
    val (dolbyAtmEnabled, onDolbyAtmChange) = rememberPreference(LastWaveDolbyAtmosEnabledKey, false)
    val (bit24Enabled, onBit24Change) = rememberPreference(LastWave24BitAudioEnabledKey, false)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.lastwave_hires_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .padding(top = topPadding)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(
                title = stringResource(R.string.lastwave_hires_title),
            ) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lastwave_hi_res_flac"),
                        title = { Text(stringResource(R.string.lastwave_hi_res_flac)) },
                        description = stringResource(R.string.lastwave_hi_res_flac_desc),
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        checked = hiResFlacEnabled,
                        onCheckedChange = onHiResFlacChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lastwave_dolby_atmos"),
                        title = { Text(stringResource(R.string.lastwave_dolby_atmos)) },
                        description = stringResource(R.string.lastwave_dolby_atmos_desc),
                        icon = { Icon(painterResource(R.drawable.brightness_high), null) },
                        checked = dolbyAtmEnabled,
                        onCheckedChange = onDolbyAtmChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lastwave_24bit_audio"),
                        title = { Text(stringResource(R.string.lastwave_24bit_audio)) },
                        description = stringResource(R.string.lastwave_24bit_audio_desc),
                        icon = { Icon(painterResource(R.drawable.equalizer), null) },
                        checked = bit24Enabled,
                        onCheckedChange = onBit24Change,
                    )
                }
            }

            Spacer(modifier = Modifier.padding(8.dp))

            PreferenceGroup(
                title = stringResource(R.string.lastwave_plugins_title),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.lastwave_plugins_title)) },
                        description = stringResource(R.string.lastwave_plugins_summary),
                        icon = { Icon(painterResource(R.drawable.experiment), null) },
                        onClick = { navController.navigate("settings/lastwave/plugins") },
                    )
                }
            }
        }
    }
}
