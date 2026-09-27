/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.update.ComponentUpdateState
import app.morphe.manager.domain.update.EcosystemUpdateCoordinator
import app.morphe.manager.domain.update.EcosystemUpdateState
import app.morphe.manager.domain.update.InstallableUpdate
import app.morphe.manager.domain.update.UpdateStatus
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Updates section settings items for the Advanced tab.
 */
@Composable
fun UpdatesSettingsItem(
    settingsViewModel: SettingsViewModel,
    onManagerPrereleasesToggle: () -> Unit,
    onYouTubeUpdate: () -> Unit,
) {
    val prefs = settingsViewModel.prefs
    val coordinator: EcosystemUpdateCoordinator = koinInject()
    val ecosystemState by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val backgroundUpdateNotifications by prefs.backgroundUpdateNotifications.getAsState()
    val automaticEcosystemUpdates by prefs.automaticEcosystemUpdates.getAsState()
    val allowMeteredUpdates by prefs.allowMeteredUpdates.getAsState()
    val externalBatchPatchEnabled by prefs.externalBatchPatchEnabled.getAsState()
    val useManagerPrereleases by prefs.useManagerPrereleases.getAsState()
    val usePatchesPrereleases by prefs.bundlePrereleasesEnabled.getAsState()
    val showPrereleaseWarning = remember { mutableStateOf(false) }

    fun applyManagerPrereleases() {
        settingsViewModel.toggleManagerPrereleases(
            currentValue = useManagerPrereleases,
            backgroundNotificationsEnabled = backgroundUpdateNotifications,
            patchesPrereleaseIds = usePatchesPrereleases,
            onCheckUpdate = onManagerPrereleasesToggle
        )
    }

    if (showPrereleaseWarning.value) {
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

    SettingsGroup {
        SettingsItem(
            onClick = { scope.launch { runCatching { coordinator.refresh(downloadAssets = true) } } },
            leadingContent = { ThemedIcon(icon = Icons.Outlined.SecurityUpdateGood) },
            title = stringResource(R.string.update_center_title),
            subtitle = refreshSubtitle,
        )

        SettingsDivider()

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

        SettingsDivider()
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

        SettingsDivider()
        EcosystemComponentItem(
            title = stringResource(R.string.update_component_youtube),
            icon = Icons.Outlined.SmartDisplay,
            state = snapshot?.youtube,
            onClick = onYouTubeUpdate,
        )

        SettingsDivider()
        EcosystemComponentItem(
            title = stringResource(R.string.update_component_patches),
            icon = Icons.Outlined.Extension,
            state = snapshot?.patches,
            onClick = { scope.launch { runCatching { coordinator.refresh(downloadAssets = true) } } },
        )

        SettingsDivider()
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
    }

    SettingsGroup {
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
            icon = Icons.Outlined.Science,
            title = stringResource(R.string.settings_advanced_updates_manager_prereleases),
            subtitle = stringResource(R.string.settings_advanced_updates_manager_prereleases_description)
        )

        SettingsDivider()

        // Allow updates on metered connections
        SettingsSwitchItem(
            checked = allowMeteredUpdates,
            onToggle = { settingsViewModel.toggleAllowMeteredUpdates(allowMeteredUpdates) },
            icon = Icons.Outlined.SignalCellularAlt,
            title = stringResource(R.string.settings_advanced_updates_allow_metered),
            subtitle = stringResource(R.string.settings_advanced_updates_allow_metered_description)
        )

        SettingsDivider()

        // Entry point other apps use to start a re-patch queue
        SettingsSwitchItem(
            checked = externalBatchPatchEnabled,
            onToggle = { settingsViewModel.toggleExternalBatchPatch(externalBatchPatchEnabled) },
            icon = Icons.Outlined.Api,
            title = stringResource(R.string.settings_advanced_external_batch_patch),
            subtitle = stringResource(R.string.settings_advanced_external_batch_patch_description)
        )

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
