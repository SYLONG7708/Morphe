/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.update.ComponentUpdateState
import app.morphe.manager.domain.update.EcosystemUpdateCoordinator
import app.morphe.manager.domain.update.EcosystemUpdateState
import app.morphe.manager.domain.update.InstallableUpdate
import app.morphe.manager.domain.update.UpdateStatus
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import app.morphe.manager.worker.UpdateCheckInterval
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

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
    val updateCheckInterval by prefs.updateCheckInterval.getAsState()
    val allowMeteredUpdates by prefs.allowMeteredUpdates.getAsState()
    val externalBatchPatchEnabled by prefs.externalBatchPatchEnabled.getAsState()
    val useManagerPrereleases by prefs.useManagerPrereleases.getAsState()
    val usePatchesPrereleases by prefs.bundlePrereleasesEnabled.getAsState()
    val showIntervalDialog = remember { mutableStateOf(false) }
    val showPrereleaseWarning = remember { mutableStateOf(false) }

    fun applyManagerPrereleases() {
        settingsViewModel.toggleManagerPrereleases(
            currentValue = useManagerPrereleases,
            backgroundNotificationsEnabled = backgroundUpdateNotifications,
            patchesPrereleaseIds = usePatchesPrereleases,
            onCheckUpdate = onManagerPrereleasesToggle
        )
    }

    if (showIntervalDialog.value) {
        UpdateCheckIntervalDialog(
            currentInterval = updateCheckInterval,
            onIntervalSelected = {
                settingsViewModel.selectUpdateInterval(it)
                showIntervalDialog.value = false
            },
            onDismiss = { showIntervalDialog.value = false }
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

        // Check frequency interval selector (non-GMS only), shown when background notifications
        // are enabled from the Notifications settings dialog
        AnimatedVisibility(
            visible = backgroundUpdateNotifications && !settingsViewModel.hasGms,
            enter = Animations.expandFadeEnter,
            exit = Animations.shrinkFadeExit
        ) {
            Column {
                SettingsDivider()

                SettingsItem(
                    onClick = { showIntervalDialog.value = true },
                    leadingContent = { ThemedIcon(icon = Icons.Outlined.Schedule) },
                    title = stringResource(R.string.settings_advanced_update_interval),
                    subtitle = stringResource(updateCheckInterval.labelResId)
                )
            }
        }

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

/**
 * Dialog shown on Android 13+ when the user enables background notifications
 * and [Manifest.permission.POST_NOTIFICATIONS] has not yet been granted.
 */
@Composable
fun NotificationPermissionDialog(
    onDismissRequest: () -> Unit,
    onPermissionResult: (granted: Boolean) -> Unit,
    title: String = stringResource(R.string.notification_permission_dialog_title),
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onPermissionResult
    )

    AppDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.allow),
                onPrimaryClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onPermissionResult(true)
                    }
                },
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismissRequest
            )
        }
    ) {
        Text(
            text = stringResource(R.string.notification_permission_dialog_description),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalDialogSecondaryTextColor.current,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Discrete-slider dialog to pick how often the background update check runs. */
@Composable
internal fun UpdateCheckIntervalDialog(
    currentInterval: UpdateCheckInterval,
    onIntervalSelected: (UpdateCheckInterval) -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.settings_advanced_update_interval_dialog_title)
    val chipSubtitle = stringResource(R.string.settings_advanced_update_interval_chip_subtitle)
    val entries = UpdateCheckInterval.entries
    var sliderIndex by remember { mutableFloatStateOf(entries.indexOf(currentInterval).toFloat()) }
    val selectedInterval = entries[sliderIndex.roundToInt().coerceIn(entries.indices)]

    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.save),
                onPrimaryClick = { onIntervalSelected(selectedInterval) },
                primaryIcon = Icons.Outlined.Check,
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismiss
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current value chip
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Defaults.CompactCornerRadius),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(selectedInterval.labelResId),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = LocalDialogTextColor.current
                    )
                    Text(
                        text = chipSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalDialogSecondaryTextColor.current,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Slider
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = sliderIndex,
                    onValueChange = { sliderIndex = it },
                    valueRange = 0f..(entries.size - 1).toFloat(),
                    steps = entries.size - 2, // n entries → n-2 internal steps
                    modifier = Modifier.fillMaxWidth()
                )

                SliderScaleLabels(
                    start = stringResource(entries.first().labelResId),
                    end = stringResource(entries.last().labelResId)
                )
            }

            // Battery optimization warning
            Notice(
                text = stringResource(R.string.settings_advanced_update_interval_battery_warning),
                tone = SemanticTone.Warning,
                icon = Icons.Outlined.BatteryAlert,
                density = NoticeDensity.Compact
            )
        }
    }
}
