/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LastWaveAutoUpdatePluginsKey
import moe.rukamori.archivetune.constants.LastWavePluginsEnabledKey
import moe.rukamori.archivetune.constants.LastWaveStreamingAddonsEnabledKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastWavePluginsSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val (pluginsEnabled, onPluginsEnabledChange) = rememberPreference(LastWavePluginsEnabledKey, false)
    val (streamingAddonsEnabled, onStreamingAddonsChange) = rememberPreference(LastWaveStreamingAddonsEnabledKey, false)
    val (autoUpdateEnabled, onAutoUpdateChange) = rememberPreference(LastWaveAutoUpdatePluginsKey, false)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.lastwave_plugins_title)) },
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
                title = stringResource(R.string.lastwave_plugins_title),
            ) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lastwave_enable_plugins"),
                        title = { Text(stringResource(R.string.lastwave_enable_plugins)) },
                        description = stringResource(R.string.lastwave_enable_plugins_desc),
                        icon = { Icon(painterResource(R.drawable.experiment), null) },
                        checked = pluginsEnabled,
                        onCheckedChange = onPluginsEnabledChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lastwave_streaming_addons"),
                        title = { Text(stringResource(R.string.lastwave_streaming_addons)) },
                        description = stringResource(R.string.lastwave_streaming_addons_desc),
                        icon = { Icon(painterResource(R.drawable.bolt), null) },
                        checked = streamingAddonsEnabled,
                        onCheckedChange = onStreamingAddonsChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lastwave_auto_update_plugins"),
                        title = { Text(stringResource(R.string.lastwave_auto_update_plugins)) },
                        description = stringResource(R.string.lastwave_auto_update_plugins_desc),
                        icon = { Icon(painterResource(R.drawable.cached), null) },
                        checked = autoUpdateEnabled,
                        onCheckedChange = onAutoUpdateChange,
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lastwave_import_plugin"),
                        title = { Text(stringResource(R.string.lastwave_import_plugin)) },
                        description = stringResource(R.string.lastwave_import_plugin_desc),
                        icon = { Icon(painterResource(R.drawable.add), null) },
                        onClick = {
                            Toast.makeText(context, R.string.lastwave_plugin_import_toast, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}
