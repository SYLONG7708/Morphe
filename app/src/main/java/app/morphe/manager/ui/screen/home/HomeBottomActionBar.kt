/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.manager.HomeAppSortMode
import app.morphe.manager.ui.screen.shared.BottomActionBar
import app.morphe.manager.ui.screen.shared.BottomActionButton
import app.morphe.manager.ui.screen.shared.BottomActionTone

/**
 * Section 5: Bottom action bar.
 * Sources | Search (optional) | Sort (optional) | Settings.
 */
@Composable
fun HomeBottomActionBar(
    modifier: Modifier = Modifier,
    onBundlesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isExpertModeEnabled: Boolean = false,
    showSearchButton: Boolean = false,
    showSortButton: Boolean = false,
    sortMode: HomeAppSortMode = HomeAppSortMode.MANUAL,
    filterMode: HomeAppFilterMode = HomeAppFilterMode.ALL,
    searchActive: Boolean = false,
    onSearchClick: () -> Unit = {},
    onSortClick: () -> Unit = {},
    onSourcesPositioned: ((Rect) -> Unit)? = null,
    onSettingsPositioned: ((Rect) -> Unit)? = null
) {
    val sourcesLabel = stringResource(R.string.sources)
    val searchLabel = stringResource(R.string.home_search_apps)
    val sortLabel = stringResource(R.string.sort)
    val settingsLabel = stringResource(R.string.settings)
    val expertModeLabel = stringResource(R.string.settings_advanced_expert_mode)

    // Only the buttons on screen take part: the optional ones give their share back to the row
    // instead of holding an empty slot, and the bar animates the neighbors into the new widths
    val labels = remember(sourcesLabel, searchLabel, sortLabel, settingsLabel, showSearchButton, showSortButton) {
        listOfNotNull(
            sourcesLabel,
            searchLabel.takeIf { showSearchButton },
            sortLabel.takeIf { showSortButton },
            settingsLabel
        )
    }

    BottomActionBar(modifier = modifier, labels = labels) {
        // Left: Sources button
        BottomActionButton(
            onClick = onBundlesClick,
            icon = Icons.Outlined.Source,
            text = sourcesLabel,
            showLabel = showLabels,
            modifier = if (onSourcesPositioned != null) {
                Modifier.onGloballyPositioned { onSourcesPositioned(it.boundsInWindow()) }
            } else Modifier
        )

        // Center: Search button
        if (showSearchButton) {
            val searchExpandedLabel = stringResource(R.string.expanded)
            val searchCollapsedLabel = stringResource(R.string.collapsed)
            BottomActionButton(
                onClick = onSearchClick,
                icon = if (searchActive) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                text = searchLabel,
                showLabel = showLabels,
                stateDescription = if (searchActive) searchExpandedLabel else searchCollapsedLabel
            )
        }

        // Sort button
        if (showSortButton) {
            val filterActive = filterMode.isActive
            BottomActionButton(
                onClick = onSortClick,
                icon = Icons.AutoMirrored.Outlined.Sort,
                text = sortLabel,
                showLabel = showLabels,
                tone = if (filterActive) BottomActionTone.Highlight else BottomActionTone.Neutral,
                stateDescription = homeAppListOptionsStateDescription(sortMode, filterMode)
            )
        }

        // Right: Settings button with expert mode indicator
        BottomActionButton(
            onClick = onSettingsClick,
            icon = if (isExpertModeEnabled) Icons.Outlined.Engineering else Icons.Outlined.Settings,
            text = settingsLabel,
            showLabel = showLabels,
            tone = if (isExpertModeEnabled) BottomActionTone.Highlight else BottomActionTone.Neutral,
            contentDescription = if (isExpertModeEnabled) "$settingsLabel, $expertModeLabel" else null,
            modifier = if (onSettingsPositioned != null) {
                Modifier.onGloballyPositioned { onSettingsPositioned(it.boundsInWindow()) }
            } else Modifier
        )
    }
}
