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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LastWaveAutoUpdatePluginsKey
import moe.rukamori.archivetune.constants.LastWaveImportedPluginsKey
import moe.rukamori.archivetune.constants.LastWavePluginsEnabledKey
import moe.rukamori.archivetune.constants.LastWaveStreamingAddonUrlKey
import moe.rukamori.archivetune.constants.LastWaveStreamingAddonsEnabledKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastWavePluginsSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (pluginsEnabled, onPluginsEnabledChange) = rememberPreference(LastWavePluginsEnabledKey, false)
    val (streamingAddonsEnabled, onStreamingAddonsChange) = rememberPreference(LastWaveStreamingAddonsEnabledKey, false)
    val (autoUpdateEnabled, onAutoUpdateChange) = rememberPreference(LastWaveAutoUpdatePluginsKey, false)
    val (addonUrl, onAddonUrlChange) = rememberPreference(LastWaveStreamingAddonUrlKey, "")
    val (importedPluginsRaw, onImportedPluginsChange) = rememberPreference(LastWaveImportedPluginsKey, "")

    val importedPlugins = remember(importedPluginsRaw) {
        importedPluginsRaw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    var showAddonUrlDialog by remember { mutableStateOf(false) }
    var showImportLwpDialog by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }

    if (showAddonUrlDialog) {
        var urlText by remember { mutableStateOf(addonUrl) }
        TextFieldDialog(
            icon = { Icon(painterResource(R.drawable.link), null) },
            title = { Text(stringResource(R.string.lastwave_streaming_addon_url)) },
            textFieldValue = urlText,
            onTextFieldValueChange = { urlText = it },
            placeholder = { Text(stringResource(R.string.lastwave_streaming_addon_placeholder)) },
            singleLine = true,
            isInputValid = { it.trim().isNotEmpty() },
            onDone = { raw ->
                onAddonUrlChange(raw.trim())
                showAddonUrlDialog = false
                Toast.makeText(context, R.string.lastwave_saved, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAddonUrlDialog = false },
        )
    }

    if (showImportLwpDialog) {
        var lwpText by remember { mutableStateOf("") }
        TextFieldDialog(
            icon = { Icon(painterResource(R.drawable.experiment), null) },
            title = { Text(stringResource(R.string.lastwave_import_lwp_title)) },
            textFieldValue = lwpText,
            onTextFieldValueChange = { lwpText = it },
            placeholder = { Text(stringResource(R.string.lastwave_import_lwp_placeholder)) },
            singleLine = true,
            isInputValid = { it.trim().isNotEmpty() },
            onDone = { raw ->
                val pluginPath = raw.trim()
                if (pluginPath.isNotEmpty()) {
                    val updated = (importedPlugins + pluginPath).joinToString("\n")
                    onImportedPluginsChange(updated)
                    showImportLwpDialog = false
                    Toast.makeText(context, context.getString(R.string.lastwave_plugin_import_success, pluginPath), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.lastwave_plugin_import_invalid, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showImportLwpDialog = false },
        )
    }

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
                    PreferenceEntry(
                        modifier = positions.modifierFor("lastwave_streaming_addon_url"),
                        title = { Text(stringResource(R.string.lastwave_streaming_addon_url)) },
                        description = addonUrl.ifBlank { stringResource(R.string.lastwave_streaming_addon_url_desc) },
                        icon = { Icon(painterResource(R.drawable.link), null) },
                        onClick = { showAddonUrlDialog = true },
                    )
                }
                item {
                    PreferenceEntry(
                        title = {
                            Text(
                                if (testingConnection) {
                                    stringResource(R.string.lastwave_testing_connection)
                                } else {
                                    stringResource(R.string.lastwave_connect_test)
                                },
                            )
                        },
                        description = stringResource(R.string.lastwave_connect_test_desc),
                        icon = { Icon(painterResource(R.drawable.cached), null) },
                        isEnabled = !testingConnection && addonUrl.isNotBlank(),
                        onClick = {
                            if (addonUrl.isBlank() || testingConnection) return@PreferenceEntry
                            testingConnection = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val conn = URL(addonUrl).openConnection() as HttpURLConnection
                                        conn.connectTimeout = 5000
                                        conn.readTimeout = 5000
                                        conn.requestMethod = "GET"
                                        conn.connect()
                                        conn.responseCode in 200..399
                                    }.getOrDefault(false)
                                }
                                testingConnection = false
                                if (ok) {
                                    Toast.makeText(context, R.string.lastwave_connection_success, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, R.string.lastwave_connection_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
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
                        onClick = { showImportLwpDialog = true },
                    )
                }
            }

            if (importedPlugins.isNotEmpty()) {
                PreferenceGroup(
                    title = stringResource(R.string.lastwave_imported_plugins),
                ) {
                    importedPlugins.forEach { plugin ->
                        item {
                            PreferenceEntry(
                                title = { Text(plugin) },
                                description = stringResource(R.string.lastwave_remove_plugin),
                                icon = { Icon(painterResource(R.drawable.delete), null) },
                                onClick = {
                                    val updated = importedPlugins.filter { it != plugin }.joinToString("\n")
                                    onImportedPluginsChange(updated)
                                    Toast.makeText(context, "Removed plugin: $plugin", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
