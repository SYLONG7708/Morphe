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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val useManagerPrereleases by prefs.useManagerPrereleases.getAsState()
    val usePatchesPrereleases by prefs.bundlePrereleasesEnabled.getAsState()

    val enabledState = stringResource(R.string.enabled)
    val disabledState = stringResource(R.string.disabled)

    // Dialog states
    val showNotificationPermissionDialog = remember { mutableStateOf(false) }
    val showIntervalDialog = remember { mutableStateOf(false) }

    // Dialogs
    if (showNotificationPermissionDialog.value) {
        NotificationPermissionDialog(
            onDismissRequest = {
                settingsViewModel.onNotificationPermissionDismissed()
                showNotificationPermissionDialog.value = false
            },
            onPermissionResult = { granted ->
                settingsViewModel.onNotificationPermissionResult(
                    granted = granted,
                    useManagerPrereleases = useManagerPrereleases,
                    patchesPrereleaseIds = usePatchesPrereleases,
                    updateCheckInterval = updateCheckInterval
                )
                showNotificationPermissionDialog.value = false
            }
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

    RichSettingsItem(
        onClick = { scope.launch { runCatching { coordinator.refresh(downloadAssets = true) } } },
        showBorder = true,
        leadingContent = { MorpheIcon(icon = Icons.Outlined.SecurityUpdateGood) },
        title = stringResource(R.string.update_center_title),
        subtitle = refreshSubtitle,
    )

    RichSettingsItem(
        onClick = {
            scope.launch {
                prefs.automaticEcosystemUpdates.update(!automaticEcosystemUpdates)
                if (!automaticEcosystemUpdates) {
                    runCatching { coordinator.refresh(downloadAssets = true) }
                }
            }
        },
        showBorder = true,
        leadingContent = { MorpheIcon(icon = Icons.Outlined.AutoMode) },
        title = stringResource(R.string.update_center_automatic),
        subtitle = stringResource(R.string.update_center_automatic_description),
        trailingContent = {
            MorpheSwitch(
                checked = automaticEcosystemUpdates,
                onCheckedChange = null,
                modifier = Modifier.semantics {
                    stateDescription = if (automaticEcosystemUpdates) enabledState else disabledState
                },
            )
        },
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
    RichSettingsItem(
        onClick = {
            settingsViewModel.toggleManagerPrereleases(
                currentValue = useManagerPrereleases,
                backgroundNotificationsEnabled = backgroundUpdateNotifications,
                patchesPrereleaseIds = usePatchesPrereleases,
                onCheckUpdate = onManagerPrereleasesToggle
            )
        },
        showBorder = true,
        leadingContent = { MorpheIcon(icon = Icons.Outlined.Science) },
        title = stringResource(R.string.settings_advanced_updates_use_prereleases),
        subtitle = stringResource(R.string.settings_advanced_updates_use_prereleases_description),
        trailingContent = {
            MorpheSwitch(
                checked = useManagerPrereleases,
                onCheckedChange = null,
                modifier = Modifier.semantics {
                    stateDescription = if (useManagerPrereleases) enabledState else disabledState
                }
            )
        }
    )

    // Background update notifications toggle
    RichSettingsItem(
        onClick = {
            settingsViewModel.toggleBackgroundNotifications(
                currentValue = backgroundUpdateNotifications,
                useManagerPrereleases = useManagerPrereleases,
                patchesPrereleaseIds = usePatchesPrereleases,
                updateCheckInterval = updateCheckInterval,
                onShowPermissionDialog = { showNotificationPermissionDialog.value = true }
            )
        },
        showBorder = true,
        leadingContent = { MorpheIcon(icon = Icons.Outlined.NotificationsActive) },
        title = stringResource(R.string.settings_advanced_updates_background_notifications),
        subtitle = stringResource(
            if (settingsViewModel.hasGms)
                R.string.settings_advanced_updates_background_notifications_description_fcm
            else
                R.string.settings_advanced_updates_background_notifications_description
        ),
        trailingContent = {
            MorpheSwitch(
                checked = backgroundUpdateNotifications,
                onCheckedChange = null,
                modifier = Modifier.semantics {
                    stateDescription =
                        if (backgroundUpdateNotifications) enabledState else disabledState
                }
            )
        }
    )

    // Check frequency interval selector (non-GMS only)
    AnimatedVisibility(
        visible = backgroundUpdateNotifications && !settingsViewModel.hasGms,
        enter = MorpheAnimations.expandFadeEnter,
        exit = MorpheAnimations.shrinkFadeExit
    ) {
        RichSettingsItem(
            onClick = { showIntervalDialog.value = true },
            showBorder = true,
            leadingContent = { MorpheIcon(icon = Icons.Outlined.Schedule) },
            title = stringResource(R.string.settings_advanced_update_interval),
            subtitle = stringResource(updateCheckInterval.labelResId)
        )
    }

    // Allow updates on metered connections
    RichSettingsItem(
        onClick = { settingsViewModel.toggleAllowMeteredUpdates(allowMeteredUpdates) },
        showBorder = true,
        leadingContent = { MorpheIcon(icon = Icons.Outlined.SignalCellularAlt) },
        title = stringResource(R.string.settings_advanced_updates_allow_metered),
        subtitle = stringResource(R.string.settings_advanced_updates_allow_metered_description),
        trailingContent = {
            MorpheSwitch(
                checked = allowMeteredUpdates,
                onCheckedChange = null,
                modifier = Modifier.semantics {
                    stateDescription = if (allowMeteredUpdates) enabledState else disabledState
                }
            )
        }
    )
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
    RichSettingsItem(
        onClick = onClick,
        showBorder = true,
        leadingContent = { MorpheIcon(icon = icon) },
        title = title,
        subtitle = "$status · $versions",
        trailingContent = if (state?.status in setOf(
                UpdateStatus.UPDATE_AVAILABLE,
                UpdateStatus.DOWNLOADED,
                UpdateStatus.WAITING_FOR_COMPATIBLE_SOURCE,
            )
        ) {
            { MorpheIcon(icon = Icons.Outlined.ChevronRight) }
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

    MorpheDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        footer = {
            MorpheDialogButtonRow(
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

/**
 * Discrete-slider dialog to pick the background update check interval.
 */
@Composable
private fun UpdateCheckIntervalDialog(
    currentInterval: UpdateCheckInterval,
    onIntervalSelected: (UpdateCheckInterval) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = UpdateCheckInterval.entries
    var sliderIndex by remember { mutableFloatStateOf(entries.indexOf(currentInterval).toFloat()) }
    val selectedInterval = entries[sliderIndex.toInt().coerceIn(entries.indices)]

    MorpheDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_advanced_update_interval_dialog_title),
        footer = {
            MorpheDialogButtonRow(
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
                shape = RoundedCornerShape(12.dp),
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
                        text = stringResource(R.string.settings_advanced_update_interval_chip_subtitle),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(entries.first().labelResId),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalDialogSecondaryTextColor.current
                    )
                    Text(
                        text = stringResource(entries.last().labelResId),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalDialogSecondaryTextColor.current
                    )
                }
            }

            // Battery optimization warning
            InfoBadge(
                text = stringResource(R.string.settings_advanced_update_interval_battery_warning),
                style = InfoBadgeStyle.Warning,
                icon = Icons.Outlined.BatteryAlert
            )
        }
    }
}
