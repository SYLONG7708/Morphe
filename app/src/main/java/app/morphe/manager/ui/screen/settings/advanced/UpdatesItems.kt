/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.morphe.manager.domain.update.ComponentUpdateState
import app.morphe.manager.domain.update.EcosystemUpdateCoordinator
import app.morphe.manager.domain.update.EcosystemUpdateState
import app.morphe.manager.domain.update.InstallableUpdate
import app.morphe.manager.domain.update.UpdateStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * Rows of a settings group for updates of the manager and its sources: pre-releases, metered
 * connections and, in expert mode, the GitHub token the update checks are made with.
 */
@Composable
fun UpdatesItems(
    settingsViewModel: SettingsViewModel,
    onManagerPrereleasesToggle: () -> Unit,
    onYouTubeUpdate: () -> Unit,
) {
    val prefs = settingsViewModel.prefs
    val coordinator: EcosystemUpdateCoordinator = koinInject()
    val ecosystemState by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val automaticEcosystemUpdates by prefs.automaticEcosystemUpdates.getAsState()
    val backgroundUpdateNotifications by prefs.backgroundUpdateNotifications.getAsState()
    val allowMeteredUpdates by prefs.allowMeteredUpdates.getAsState()
    val useManagerPrereleases by prefs.useManagerPrereleases.getAsState()
    val usePatchesPrereleases by prefs.bundlePrereleasesEnabled.getAsState()
    val useExpertMode by prefs.useExpertMode.getAsState()
    val showPrereleaseWarning = remember { mutableStateOf(false) }

    fun applyManagerPrereleases() {
        settingsViewModel.toggleManagerPrereleases(
            currentValue = useManagerPrereleases,
            backgroundNotificationsEnabled = backgroundUpdateNotifications,
            patchesPrereleaseIds = usePatchesPrereleases,
            onCheckUpdate = onManagerPrereleasesToggle
        )
    }

    DialogHost(visible = showPrereleaseWarning.value) {
        ConfirmDialog(
            title = stringResource(R.string.settings_advanced_updates_prerelease_warning_title),
            message = stringResource(R.string.settings_advanced_updates_prerelease_warning_message),
            primaryText = stringResource(R.string.enable),
            isPrimaryDestructive = false,
            onDismiss = { showPrereleaseWarning.value = false },
            onConfirm = {
                showPrereleaseWarning.value = false
                applyManagerPrereleases()
            }
        )
    }

    LaunchedEffect(Unit) {
        if (ecosystemState is EcosystemUpdateState.Idle) {
            runCatching { coordinator.refresh(downloadAssets = true) }
        }
    }

    val snapshot = (ecosystemState as? EcosystemUpdateState.Ready)?.snapshot
    val refreshSubtitle = when (val state = ecosystemState) {
        EcosystemUpdateState.Idle -> stringResource(R.string.update_center_idle)
        EcosystemUpdateState.Checking -> stringResource(R.string.update_center_checking)
        is EcosystemUpdateState.Ready -> stringResource(R.string.update_center_verified)
        is EcosystemUpdateState.Failure ->
            stringResource(R.string.update_center_failed, state.message)
    }

        SettingsItem(
            onClick = { scope.launch { runCatching { coordinator.refresh(downloadAssets = true) } } },
            leadingContent = { ThemedIcon(icon = Icons.Outlined.SecurityUpdateGood) },
            title = stringResource(R.string.update_center_title),
            subtitle = refreshSubtitle,
        )


        SettingsSwitchItem(
            checked = automaticEcosystemUpdates,
            onToggle = {
                scope.launch {
                    val enabled = !automaticEcosystemUpdates
                    prefs.automaticEcosystemUpdates.update(enabled)
                    if (enabled) {
                        runCatching { coordinator.refresh(downloadAssets = true) }
                    }
                }
            },
            icon = Icons.Outlined.AutoMode,
            title = stringResource(R.string.update_center_automatic),
            subtitle = stringResource(R.string.update_center_automatic_description),
        )

        EcosystemComponentItem(
            title = stringResource(R.string.update_component_manager),
            icon = Icons.Outlined.SystemUpdate,
            state = snapshot?.manager,
            onClick = {
                scope.launch {
                    runCatching { coordinator.installPrepared(InstallableUpdate.MANAGER) }
                }
            },
        )

        EcosystemComponentItem(
            title = stringResource(R.string.update_component_youtube),
            icon = Icons.Outlined.SmartDisplay,
            state = snapshot?.youtube,
            onClick = onYouTubeUpdate,
        )

        EcosystemComponentItem(
            title = stringResource(R.string.update_component_patches),
            icon = Icons.Outlined.Extension,
            state = snapshot?.patches,
            onClick = { scope.launch { runCatching { coordinator.refresh(downloadAssets = true) } } },
        )

        EcosystemComponentItem(
            title = stringResource(R.string.update_component_microg),
            icon = Icons.Outlined.Android,
            state = snapshot?.microg,
            onClick = {
                scope.launch {
                    runCatching { coordinator.installPrepared(InstallableUpdate.MICROG) }
                }
            },
        )


    // Use manager prereleases toggle
    SettingsSwitchItem(
        checked = useManagerPrereleases,
        onToggle = {
            if (useManagerPrereleases) {
                applyManagerPrereleases()
            } else {
                // Explain what pre-release means, and that patches are separate, before flipping it on
                showPrereleaseWarning.value = true
            }
        },
        icon = MaterialSymbols.Rounded.Science,
        title = stringResource(R.string.settings_advanced_updates_manager_prereleases),
        subtitle = stringResource(R.string.settings_advanced_updates_manager_prereleases_description)
    )

    // Allow updates on metered connections
    SettingsSwitchItem(
        checked = allowMeteredUpdates,
        onToggle = { settingsViewModel.toggleAllowMeteredUpdates(allowMeteredUpdates) },
        icon = MaterialSymbols.Rounded.Signal_cellular_alt,
        title = stringResource(R.string.settings_advanced_updates_allow_metered),
        subtitle = stringResource(R.string.settings_advanced_updates_allow_metered_description)
    )

    if (useExpertMode) {
        GitHubPatSettingsItem(settingsViewModel)
    }
}

@Composable
private fun EcosystemComponentItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    state: ComponentUpdateState?,
    onClick: () -> Unit,
) {
    val status = when (state?.status) {
        UpdateStatus.UP_TO_DATE -> stringResource(R.string.update_status_up_to_date)
        UpdateStatus.UPDATE_AVAILABLE -> stringResource(R.string.update_status_available)
        UpdateStatus.DOWNLOADED -> stringResource(R.string.update_status_downloaded)
        UpdateStatus.INSTALLING -> stringResource(R.string.update_status_installing)
        UpdateStatus.WAITING_FOR_COMPATIBLE_SOURCE ->
            stringResource(R.string.update_status_waiting_source)
        UpdateStatus.NOT_INSTALLED -> stringResource(R.string.update_status_not_installed)
        UpdateStatus.UNSUPPORTED -> stringResource(R.string.update_status_unsupported)
        UpdateStatus.ERROR -> stringResource(R.string.update_status_error)
        UpdateStatus.CHECKING, null -> stringResource(R.string.update_center_checking)
    }
    val versions = stringResource(
        R.string.update_center_versions,
        state?.installedVersion ?: "—",
        state?.availableVersion ?: "—",
    )
    SettingsItem(
        onClick = onClick,
        leadingContent = { ThemedIcon(icon = icon) },
        title = title,
        subtitle = "$status · $versions",
        trailingContent = if (state?.status in setOf(
                UpdateStatus.UPDATE_AVAILABLE,
                UpdateStatus.DOWNLOADED,
                UpdateStatus.WAITING_FOR_COMPATIBLE_SOURCE,
            )
        ) {
            { ThemedIcon(icon = Icons.Outlined.ChevronRight) }
        } else {
            null
        },
    )
}
