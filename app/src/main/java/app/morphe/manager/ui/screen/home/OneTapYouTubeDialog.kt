/*
 * SyMorphe one-tap UIS7870 workflow, 2026.
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.update.UpdateStatus
import app.morphe.manager.ui.screen.shared.LocalDialogSecondaryTextColor
import app.morphe.manager.ui.screen.shared.LocalDialogTextColor
import app.morphe.manager.ui.screen.shared.MorpheDialog
import app.morphe.manager.ui.viewmodel.OneTapHubUiState

@Composable
fun OneTapYouTubeDialog(
    state: OneTapHubUiState,
    onUseLocal: () -> Unit,
    onUseRecommended: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    MorpheDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.one_tap_title),
        dismissOnClickOutside = false,
        compactPadding = true,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !state.checking && !state.working,
                ) {
                    Text(stringResource(R.string.one_tap_refresh))
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.working,
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    ) {
        Text(
            text = stringResource(R.string.one_tap_description),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalDialogSecondaryTextColor.current,
        )
        Spacer(Modifier.height(16.dp))

        if (state.checking) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp))
                Text(
                    text = stringResource(R.string.one_tap_checking),
                    modifier = Modifier.padding(start = 12.dp),
                    color = LocalDialogTextColor.current,
                )
            }
            return@MorpheDialog
        }

        OneTapStatusRow(
            icon = {
                Icon(
                    Icons.Outlined.SmartDisplay,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = stringResource(R.string.one_tap_local_youtube),
            value = state.localYouTubeVersion
                ?: stringResource(R.string.one_tap_not_installed),
            detail = when {
                state.localYouTubeVersion == null ->
                    stringResource(R.string.one_tap_local_not_found)
                state.localYouTubeCompatible ->
                    stringResource(R.string.one_tap_local_ready)
                else ->
                    stringResource(
                        R.string.one_tap_local_incompatible,
                        state.localYouTubeVersion,
                    )
            },
        )
        OneTapStatusRow(
            icon = {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = stringResource(R.string.one_tap_recommended_youtube),
            value = state.recommendedYouTubeVersion
                ?: stringResource(R.string.one_tap_unavailable),
            detail = stringResource(R.string.one_tap_recommended_detail),
        )
        OneTapStatusRow(
            icon = {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = stringResource(R.string.one_tap_microg),
            value = state.microgInstalledVersion
                ?: stringResource(R.string.one_tap_not_installed),
            detail = microgDetail(state),
        )

        state.existingPatchedYouTubePackage?.let { packageName ->
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.one_tap_existing_patched_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.one_tap_existing_patched_detail,
                            packageName,
                            state.existingPatchedYouTubeVersion
                                ?: stringResource(R.string.one_tap_unavailable),
                            state.existingPatchedMicrogVersion
                                ?: stringResource(R.string.one_tap_not_installed),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        state.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state.working) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.one_tap_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = LocalDialogSecondaryTextColor.current,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        FilledTonalButton(
            onClick = onUseLocal,
            enabled = state.localYouTubeCompatible && !state.working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.one_tap_use_local))
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onUseRecommended,
            enabled = state.recommendedYouTubeVersion != null && !state.working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.one_tap_use_recommended))
        }
    }
}

@Composable
private fun OneTapStatusRow(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalDialogTextColor.current,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = LocalDialogSecondaryTextColor.current,
            )
        }
    }
}

@Composable
private fun microgDetail(state: OneTapHubUiState): String = when (state.microgStatus) {
    UpdateStatus.UP_TO_DATE -> stringResource(R.string.one_tap_microg_ready)
    UpdateStatus.DOWNLOADED,
    UpdateStatus.UPDATE_AVAILABLE -> stringResource(
        R.string.one_tap_microg_update_ready,
        state.microgAvailableVersion ?: stringResource(R.string.one_tap_unavailable),
    )
    UpdateStatus.UNSUPPORTED -> stringResource(R.string.one_tap_microg_incompatible)
    UpdateStatus.ERROR -> stringResource(R.string.one_tap_microg_prepare_failed)
    else -> stringResource(R.string.one_tap_microg_will_install)
}
