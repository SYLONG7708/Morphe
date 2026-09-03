/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.content.pm.PackageInfo
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.screen.shared.Animations
import app.morphe.manager.ui.theme.LocalAppCardColorResolver
import app.morphe.manager.ui.theme.LocalMonochromeTheme
import app.morphe.manager.ui.theme.MonochromeThemeDefaults
import app.morphe.manager.util.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// A verdict answered from cache lands within a frame, so the badge waits rather than flashing
private const val INSTALL_VERIFICATION_BADGE_DELAY_MS = 400L

private data class HomeAppCardStyle(
    val monochrome: Boolean,
    val colorResolver: AppCardColorResolver?,
    val iconSize: Dp,
    val titleColor: Color,
    val subtitleColor: Color,
    val titleStyle: TextStyle,
    val subtitleStyle: TextStyle,
    val chipContainerColor: Color,
    val chipContentColor: Color,
    val cardColor: Color,
    val cardRadius: Dp = 24.dp,
    val cardHeight: Dp = 80.dp,
    val contentPadding: Dp = 16.dp,
    val contentSpacing: Dp = 16.dp
) {
    /**
     * Applies the card colors chosen in the appearance settings to [bundleColors], the palette
     * declared by this card's own bundle. Stops bound to the bundle are derived from it, so the
     * result stays per-app even when the rest of the gradient is fixed.
     */
    fun cardColors(bundleColors: List<Color>): List<Color> =
        colorResolver?.resolve(bundleColors) ?: bundleColors

    /**
     * This style adjusted to the gradient it will be drawn on. White reads on the colors a bundle
     * declares for itself, but the appearance settings let a card be any color, including one
     * light enough to swallow it.
     */
    fun onCard(bundleColors: List<Color>): HomeAppCardStyle {
        if (monochrome) return this

        val fill = cardColors(bundleColors).blend()
        if (fill.requiresLightContent()) return this

        return copy(
            titleColor = Color.Black,
            subtitleColor = Color.Black.copy(alpha = subtitleColor.alpha),
            chipContainerColor = Color.Black.copy(alpha = chipContainerColor.alpha),
            chipContentColor = Color.Black
        )
    }
}

@Composable
private fun homeAppCardStyle(subtitleAlpha: Float = 0.75f): HomeAppCardStyle {
    val monochrome = LocalMonochromeTheme.current
    val titleShadow = MonochromeThemeDefaults.textShadow(
        Shadow(
            color = Color.Black.copy(alpha = 0.4f),
            offset = Offset(0f, 2f),
            blurRadius = 4f
        )
    )
    val subtitleShadow = MonochromeThemeDefaults.textShadow(
        Shadow(
            color = Color.Black.copy(alpha = 0.4f),
            offset = Offset(0f, 1f),
            blurRadius = 2f
        )
    )

    return HomeAppCardStyle(
        monochrome = monochrome,
        colorResolver = LocalAppCardColorResolver.current,
        iconSize = 60.dp,
        titleColor = if (monochrome) MaterialTheme.colorScheme.onSurface else Color.White,
        subtitleColor = if (monochrome) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            Color.White.copy(alpha = subtitleAlpha)
        },
        titleStyle = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            shadow = titleShadow
        ),
        subtitleStyle = MaterialTheme.typography.bodyMedium.copy(shadow = subtitleShadow),
        chipContainerColor = if (monochrome) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            Color.White.copy(alpha = 0.20f)
        },
        chipContentColor = if (monochrome) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            Color.White
        },
        cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * Shared icon + text content for [AppCardLayout] rows.
 *
 * @param packageName    Package name used for icon lookup when [packageInfo] is null;
 *   null renders the glass placeholder without resolving an icon.
 * @param packageInfo    Resolved [PackageInfo]; when non-null [packageName] is ignored for the icon.
 * @param displayName    Primary label shown in bold.
 * @param subtitle       Secondary line shown below [displayName]; null → not rendered.
 * @param gradientColors Gradient palette forwarded to [AppIcon] placeholder, unless the user
 *   picked fixed card colors in the appearance settings.
 */
@Composable
internal fun RowScope.AppCardContent(
    packageName: String?,
    packageInfo: PackageInfo?,
    displayName: String,
    subtitle: String?,
    gradientColors: List<Color>
) {
    val cardStyle = homeAppCardStyle().onCard(gradientColors)

    AppIcon(
        packageInfo = packageInfo,
        packageName = if (packageInfo == null) packageName else null,
        contentDescription = null,
        modifier = Modifier.size(cardStyle.iconSize),
        preferredSource = AppDataSource.PATCHED_APK,
        placeholderGradientColors = cardStyle.cardColors(gradientColors),
        placeholderInnerPadding = 6.dp
    )

    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = displayName,
            style = cardStyle.titleStyle,
            color = cardStyle.titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (subtitle != null) {
            Text(
                // Matches the badge-height subtitle row of the installed cards, so names line up
                // across every card in the list
                modifier = Modifier
                    .height(statusBadgeHeight)
                    .wrapContentHeight(Alignment.CenterVertically),
                text = subtitle,
                style = cardStyle.subtitleStyle,
                color = cardStyle.subtitleColor
            )
        }
    }
}

/**
 * Installed app card with gradient background.
 */
@Composable
fun InstalledAppCard(
    installedApp: InstalledApp,
    packageInfo: PackageInfo?,
    displayName: String,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isClone: Boolean = false,
    hasUpdate: Boolean = false,
    isAppDeleted: Boolean = false,
    isInstallStateNotPatched: Boolean = false,
    isInstallStateUnknown: Boolean = false,
    isInstallStatePending: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val cardStyle = homeAppCardStyle(subtitleAlpha = 0.85f).onCard(gradientColors)
    val showsUpdateBadge = hasUpdate &&
            !isAppDeleted &&
            !isInstallStateNotPatched &&
            !isInstallStateUnknown &&
            !isInstallStatePending

    val versionLabel = stringResource(R.string.version)
    val cloneLabel = stringResource(R.string.clone)
    val installedLabel = stringResource(R.string.installed)
    val updateAvailableLabel = stringResource(R.string.update_available)
    val deletedLabel = stringResource(R.string.uninstalled)
    val replacementLabel = stringResource(R.string.home_unpatched_version_installed)
    val replacementBadgeLabel = stringResource(R.string.home_unpatched_badge)
    val unverifiedLabel = stringResource(R.string.home_unverified)
    val pendingLabel = stringResource(R.string.home_install_verification_pending)

    val showsPendingBadge = remember { mutableStateOf(false) }
    LaunchedEffect(isInstallStatePending) {
        if (!isInstallStatePending) {
            showsPendingBadge.value = false
            return@LaunchedEffect
        }
        delay(INSTALL_VERIFICATION_BADGE_DELAY_MS.milliseconds)
        showsPendingBadge.value = true
    }

    val version = remember(packageInfo, installedApp, isAppDeleted) {
        val raw = packageInfo?.versionName ?: installedApp.version
        raw.withVersionPrefix()
    }

    val contentDesc = remember(
        displayName,
        version,
        versionLabel,
        isClone,
        cloneLabel,
        installedLabel,
        showsUpdateBadge,
        updateAvailableLabel,
        isAppDeleted,
        deletedLabel,
        isInstallStateNotPatched,
        replacementLabel,
        isInstallStateUnknown,
        unverifiedLabel,
        showsPendingBadge.value,
        pendingLabel
    ) {
        buildString {
            append(displayName)
            if (isClone) append(", $cloneLabel")
            if (version.isNotEmpty()) {
                append(", $versionLabel $version")
            }
            append(", ")
            append(
                when {
                    showsPendingBadge.value -> pendingLabel
                    isInstallStateNotPatched -> replacementLabel
                    isAppDeleted -> deletedLabel
                    isInstallStateUnknown -> unverifiedLabel
                    else -> installedLabel
                }
            )
            if (showsUpdateBadge) append(", $updateAvailableLabel")
        }
    }

    AppCardLayout(
        gradientColors = gradientColors,
        enabled = true,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics {
            role = Role.Button
            this.contentDescription = contentDesc
        }
    ) {
        // App icon
        AppIcon(
            packageInfo = packageInfo,
            // The install's own package: a clone carries its own icon, which is regularly the
            // whole point of keeping several copies of an app apart
            packageName = installedApp.currentPackageName,
            contentDescription = null,
            modifier = Modifier.size(cardStyle.iconSize),
            preferredSource = AppDataSource.INSTALLED,
            // A record can outlive every artifact carrying its icon, and the glass placeholder is
            // what the rest of the list shows in that case
            placeholderGradientColors = cardStyle.cardColors(gradientColors),
            placeholderInnerPadding = 6.dp
        )

        // App info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // App name
            Text(
                text = displayName,
                style = cardStyle.titleStyle,
                color = cardStyle.titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Version + deleted status + update chip, both pinned to the card edge
            Row(
                // Badge height is reserved whether a badge is showing, otherwise the row
                // grows around it and nudges the app name above out of place
                modifier = Modifier.height(statusBadgeHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Says what the card is rather than how its install is doing, so it leads the
                // row and stays put while the state badges at the end come and go. Wordless
                // because the badges it shares the row with need the width for their own labels
                if (isClone) {
                    StatusBadge(
                        text = null,
                        icon = Icons.Outlined.ContentCopy,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }

                // Fills the row so the chip is pinned to the card edge rather than trailing
                // a version string of whatever length
                Text(
                    modifier = Modifier.weight(1f),
                    text = version,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = cardStyle.subtitleStyle,
                    color = cardStyle.subtitleColor
                )

                // Frosted-glass colors: a white semi-transparent fill reads on any accent
                // color the card's bundle brings, and on the user's dynamic theme
                if (isAppDeleted && !isInstallStateNotPatched) {
                    StatusBadge(
                        text = stringResource(R.string.uninstalled),
                        icon = Icons.Outlined.DeleteOutline,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }

                if (isInstallStateNotPatched) {
                    StatusBadge(
                        text = replacementBadgeLabel,
                        icon = Icons.Outlined.AutoFixHigh,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }

                if (isInstallStateUnknown) {
                    StatusBadge(
                        text = unverifiedLabel,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }

                if (showsPendingBadge.value) {
                    StatusBadge(
                        text = pendingLabel,
                        icon = Icons.Outlined.HourglassEmpty,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }

                AnimatedVisibility(
                    visible = showsUpdateBadge,
                    enter = Animations.expandHorizFadeIn,
                    exit = Animations.shrinkHorizFadeOut
                ) {
                    StatusBadge(
                        text = stringResource(R.string.update),
                        icon = Icons.Outlined.ArrowUpward,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }
            }
        }
    }
}

/**
 * App button with gradient background.
 */
@Composable
fun AppButton(
    packageName: String,
    displayName: String,
    packageInfo: PackageInfo?,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    val notPatchedText = stringResource(R.string.home_not_patched_yet)
    val disabledText = stringResource(R.string.disabled)

    // Build content description for accessibility
    val contentDesc = remember(displayName, notPatchedText, disabledText, enabled) {
        buildString {
            append(displayName)
            append(", ")
            append(notPatchedText)
            if (!enabled) {
                append(", ")
                append(disabledText)
            }
        }
    }

    AppCardLayout(
        gradientColors = gradientColors,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics {
            role = Role.Button
            this.contentDescription = contentDesc
            if (!enabled) {
                stateDescription = disabledText
            }
        }
    ) {
        AppCardContent(
            packageName = packageName,
            packageInfo = packageInfo,
            displayName = displayName,
            subtitle = notPatchedText,
            gradientColors = gradientColors,
        )
    }
}

/**
 * Shared content layout for app cards and buttons.
 *
 * Uses a frosted glass effect built from two passes:
 * - a diagonal sweep carrying the card colors from the bottom-start tint to the top-end accent
 * - a gradient border
 */
@Composable
internal fun AppCardLayout(
    modifier: Modifier = Modifier,
    gradientColors: List<Color>,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val cardStyle = homeAppCardStyle().onCard(gradientColors)
    val shape = RoundedCornerShape(cardStyle.cardRadius)
    val view = LocalView.current

    // Long-pressing a card always means the same thing here, so the gesture is announced
    // rather than left as an unlabeled action the screen reader cannot describe
    val longClickLabel = stringResource(R.string.accessibility_select_app)
        .takeIf { onLongClick != null }

    val contentAlpha = if (enabled) 1f else 0.45f
    val colors = cardStyle.cardColors(gradientColors)
    val baseColor = colors.firstOrNull() ?: Color.White
    val midColor = colors.getOrElse(1) { baseColor }
    val endColor = colors.lastOrNull() ?: baseColor

    // Disabled state fades everything
    val glassAlpha  = if (enabled) 1f else 0.5f
    val borderAlpha = if (enabled) 1f else 0.4f

    // Press scale animation
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(cardStyle.cardHeight)
            .pressScale(
                interactionSource = interactionSource,
                label = "card_press_scale"
            )
            .clip(shape)
            // Brushes are rebuilt only when the size or the palette changes, so scrolling a list
            // of cards does not reallocate them on every frame
            .drawWithCache {
                val w  = size.width
                val h  = size.height
                val cr = CornerRadius(cardStyle.cardRadius.toPx())
                val rtl = layoutDirection.isRtl

                if (cardStyle.monochrome) {
                    return@drawWithCache onDrawWithContent {
                        drawRoundRect(
                            color = cardStyle.cardColor,
                            cornerRadius = cr
                        )

                        drawContent()
                    }
                }

                // One sweep from the bottom-start tint through to the top-end accent. Every
                // translucent layer costs the GPU a full blend pass over the card, and a list of
                // them scrolling is what pushed the frame past its budget
                val glass = Brush.linearGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.70f * glassAlpha),
                        midColor.copy(alpha = 0.58f * glassAlpha),
                        endColor.copy(alpha = 0.64f * glassAlpha)
                    ),
                    start = Offset(startEdgeX(w, rtl), h),
                    end   = Offset(endEdgeX(w, rtl), 0f)
                )

                // Border: bright top-start → faded bottom-end
                val border = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f * borderAlpha),
                        midColor.copy(alpha = 0.30f * borderAlpha),
                        endColor.copy(alpha = 0.15f * borderAlpha),
                        Color.White.copy(alpha = 0.20f * borderAlpha)
                    ),
                    start = Offset(startEdgeX(w, rtl), 0f),
                    end   = Offset(endEdgeX(w, rtl), h)
                )
                val borderStroke = Stroke(width = 1.5.dp.toPx())

                onDrawWithContent {
                    drawRoundRect(brush = glass, cornerRadius = cr)

                    drawContent()

                    drawRoundRect(
                        brush = border,
                        cornerRadius = cr,
                        style = borderStroke
                    )
                }
            }
            .combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
                onLongClickLabel = longClickLabel,
                onLongClick = if (onLongClick != null) {
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongClick()
                    }
                } else null
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = cardStyle.contentPadding)
                .graphicsLayer { alpha = contentAlpha },
            horizontalArrangement = Arrangement.spacedBy(cardStyle.contentSpacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Shimmer loading animation for app cards.
 */
@Composable
fun AppLoadingCard(
    gradientColors: List<Color>,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")

    // Pulse animation for gradient background
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Shimmer animation
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val cardStyle = homeAppCardStyle().onCard(gradientColors)
    val shape = RoundedCornerShape(cardStyle.cardRadius)
    val rtl = isRtl()

    // Skeleton rows carry the height of the text they stand in for, so the card does not
    // re-lay-out its content the moment the real app resolves
    val titleRowHeight = with(LocalDensity.current) { cardStyle.titleStyle.lineHeight.toDp() }

    // Follows the content the card settled on, so the skeleton stays visible on a light gradient
    val skeletonColor = cardStyle.titleColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(cardStyle.cardHeight)
    ) {
        // Base gradient background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .then(
                    if (cardStyle.monochrome) {
                        Modifier.background(cardStyle.cardColor)
                    } else {
                        Modifier.background(
                            brush = Brush.linearGradient(
                                colors = cardStyle.cardColors(gradientColors)
                                    .map { it.copy(alpha = pulseAlpha) },
                                start = Offset(startEdgeX(1000f, rtl), 0f),
                                end = Offset(endEdgeX(1000f, rtl), 0f)
                            )
                        )
                    }
                )
        )

        // Shimmer overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .drawBehind {
                    drawDiagonalShimmer(
                        progress = (shimmerOffset + 1f) / 3f,
                        color = skeletonColor.copy(alpha = 0.3f)
                    )
                }
        )

        // Content skeleton
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = cardStyle.contentPadding),
            horizontalArrangement = Arrangement.spacedBy(cardStyle.contentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon skeleton, inset and rounded like the glass placeholder it stands in for
            ShimmerBox(
                modifier = Modifier
                    .size(cardStyle.iconSize)
                    .padding(6.dp),
                shape = RoundedCornerShape(percent = 20),
                baseColor = skeletonColor.copy(alpha = 0.2f)
            )

            // Text skeleton
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier.height(titleRowHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(20.dp),
                        shape = RoundedCornerShape(4.dp),
                        baseColor = skeletonColor.copy(alpha = 0.25f)
                    )
                }
                Box(
                    modifier = Modifier.height(statusBadgeHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(14.dp),
                        shape = RoundedCornerShape(4.dp),
                        baseColor = skeletonColor.copy(alpha = 0.15f)
                    )
                }
            }
        }
    }
}
