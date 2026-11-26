package com.open360.wear.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonColors
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.RadioButtonColors
import androidx.wear.compose.material3.RadioButtonDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButtonColors
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import com.open360.core.model.CaptureMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.ui.Insta360Icons
import com.open360.ui.camera.CameraStatusText

/**
 * Watch port of mobile's `TopBarStatusDot`: hollow ring when disconnected, smooth color
 * transitions, and GPU-composited recording pulse via [rememberWearRecordingPulseAlpha].
 */
@Composable
fun WearStatusDot(
    dotState: CameraStatusText.StatusDotState,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp
) {
    val reducedMotion = rememberWearReducedMotion()
    if (dotState == CameraStatusText.StatusDotState.DISCONNECTED) {
        Box(
            modifier = modifier
                .size(size)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        )
        return
    }

    val targetDotColor = when (dotState) {
        CameraStatusText.StatusDotState.RECORDING -> MaterialTheme.wearStatusColors.recording
        CameraStatusText.StatusDotState.CONNECTED -> MaterialTheme.wearStatusColors.ready
        CameraStatusText.StatusDotState.STANDBY -> MaterialTheme.wearStatusColors.waiting
        CameraStatusText.StatusDotState.CONNECTING -> MaterialTheme.colorScheme.primary
        CameraStatusText.StatusDotState.DISCONNECTED -> Color.Transparent
    }
    val animatedDotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = tween(durationMillis = if (reducedMotion) 140 else 180, easing = WearEaseOutCubic),
        label = "wearDotColor"
    )
    val pulseAlpha = rememberWearRecordingPulseAlpha(
        active = dotState == CameraStatusText.StatusDotState.RECORDING
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = pulseAlpha.value }
            .clip(CircleShape)
            .background(animatedDotColor)
    )
}

/**
 * Watch port of mobile's `TopBarMetricChip` / `StatusPill`: pill badge with hairline border,
 * optional leading dot, drawable or vector icon, problem highlight, and tabular figures.
 */
@Composable
fun WearMetricPill(
    text: String,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    icon: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
    iconTint: Color? = null,
    textColor: Color? = null,
    dotColor: Color? = null,
    dotState: CameraStatusText.StatusDotState? = null,
    isProblem: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    maxLines: Int = 1
) {
    val bgColor = if (isProblem) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val resolvedTextColor = textColor ?: if (isProblem) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val resolvedIconTint = when {
        iconTint != null -> iconTint
        isProblem -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (isProblem) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val border = BorderStroke(width = 1.dp, color = borderColor)

    val containerModifier = if (transformation != null) {
        val painter = remember(transformation, bgColor, border) {
            transformation.createContainerPainter(
                painter = ColorPainter(bgColor),
                shape = CircleShape,
                border = border
            )
        }
        Modifier
            .graphicsLayer { with(transformation) { applyContainerTransformation() } }
            .then(modifier)
            .drawBehind { with(painter) { draw(size) } }
            .graphicsLayer {
                this.shape = CircleShape
                with(transformation) { applyContentTransformation() }
                clip = true
            }
            .padding(contentPadding)
    } else {
        modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(border = border, shape = CircleShape)
            .padding(contentPadding)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        modifier = containerModifier
    ) {
        if (dotState != null) {
            WearStatusDot(dotState = dotState, size = 6.dp)
        } else if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = resolvedIconTint,
                modifier = Modifier.size(13.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedIconTint,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = text,
            style = textStyle.merge(WearTabularFigures),
            fontWeight = FontWeight.SemiBold,
            color = resolvedTextColor,
            maxLines = maxLines,
            softWrap = maxLines > 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Watch equivalent of mobile's `SurfaceCard`: `surfaceContainer` card with rounded editorial
 * corners (`18.dp`) and a crisp `1.dp` `outlineVariant` hairline border, supporting Wear
 * `SurfaceTransformation` inside `TransformingLazyColumn`.
 */
@Composable
fun WearSurfaceCard(
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    )

    val containerModifier = if (transformation != null) {
        val painter = remember(transformation, containerColor, shape, border) {
            transformation.createContainerPainter(
                painter = ColorPainter(containerColor),
                shape = shape,
                border = border
            )
        }
        Modifier
            .graphicsLayer { with(transformation) { applyContainerTransformation() } }
            .then(modifier)
            .fillMaxWidth()
            .drawBehind { with(painter) { draw(size) } }
            .graphicsLayer {
                this.shape = shape
                with(transformation) { applyContentTransformation() }
                clip = true
            }
    } else {
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .border(border = border, shape = shape)
    }

    Column(
        modifier = containerModifier.padding(contentPadding),
        content = content
    )
}

/**
 * Renders the resolved Insta360 camera silhouette inside a circular `surfaceContainerHigh` badge
 * with a crisp hairline border and optional activity ring while scanning, connecting, or waking.
 */
@Composable
fun WearCameraModelAvatar(
    model: Insta360CameraModel,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    spinning: Boolean = false,
    reserveRingSpace: Boolean = spinning,
    ringColor: Color = MaterialTheme.colorScheme.primary
) {
    val reducedMotion = rememberWearReducedMotion()
    val badgeSize = if (reserveRingSpace) size * 0.78f else size
    val iconSize = badgeSize * 0.74f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (spinning) {
            if (reducedMotion) {
                CircularProgressIndicator(
                    progress = { 0.75f },
                    modifier = Modifier.fillMaxSize(),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = ringColor,
                        trackColor = ringColor.copy(alpha = 0.16f)
                    ),
                    strokeWidth = 3.dp
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = ringColor,
                        trackColor = ringColor.copy(alpha = 0.16f)
                    ),
                    strokeWidth = 3.dp
                )
            }
        }
        Box(
            modifier = Modifier
                .size(badgeSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(Insta360Icons.cameraDrawableRes(model)),
                contentDescription = model.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Watch port of mobile's `TactileShutterButton`: GPU press scale (`0.95f`), GPU `drawBehind`
 * spring morph between round trigger and rounded stop square, subtle halo, and Wear progress ring
 * when waiting on camera confirmation.
 */
@Composable
fun WearTactileShutterButton(
    currentMode: CaptureMode,
    isRecording: Boolean,
    isBusy: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val reducedMotion = rememberWearReducedMotion()
    val pressScale by rememberWearPressScale(interactionSource, targetScale = 0.95f)

    val ringStroke = (size * 0.041f).coerceIn(2.5.dp, 3.5.dp)
    val innerIdleSize = size * 0.65f
    val innerRecSize = size * 0.33f
    val innerRecCorner = (size * 0.095f).coerceIn(4.dp, 8.dp)
    val innerIdleCorner = innerIdleSize / 2f

    val morphSpec = if (reducedMotion) {
        snap<Dp>()
    } else {
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 450f)
    }
    val innerCornerRadiusState = animateDpAsState(
        targetValue = if (isRecording) innerRecCorner else innerIdleCorner,
        animationSpec = morphSpec,
        label = "wearShutterCorner"
    )
    val innerSizeState = animateDpAsState(
        targetValue = if (isRecording) innerRecSize else innerIdleSize,
        animationSpec = morphSpec,
        label = "wearShutterInnerSize"
    )

    val isVideo = currentMode == CaptureMode.VIDEO || isRecording
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val ringColor = when {
        !enabled -> disabledColor
        isVideo -> MaterialTheme.wearStatusColors.recording
        else -> MaterialTheme.colorScheme.outline
    }
    val innerColor = when {
        !enabled -> disabledColor
        isVideo -> MaterialTheme.wearStatusColors.recording
        else -> MaterialTheme.colorScheme.onSurface
    }
    val photoBorderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.08f))
            .border(ringStroke, ringColor, CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isBusy,
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isBusy) {
            val indicatorColor = if (isVideo) {
                MaterialTheme.wearStatusColors.recording
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            if (reducedMotion) {
                CircularProgressIndicator(
                    progress = { 0.75f },
                    modifier = Modifier.size(innerIdleSize * 0.8f),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = indicatorColor,
                        trackColor = indicatorColor.copy(alpha = 0.16f)
                    ),
                    strokeWidth = 3.dp
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(innerIdleSize * 0.8f),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = indicatorColor,
                        trackColor = indicatorColor.copy(alpha = 0.16f)
                    ),
                    strokeWidth = 3.dp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(innerIdleSize)
                    .drawBehind {
                        val side = innerSizeState.value.toPx()
                        val radius = innerCornerRadiusState.value.toPx()
                        val topLeft = Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f)
                        val rectSize = Size(side, side)
                        val corner = CornerRadius(radius, radius)
                        drawRoundRect(
                            color = innerColor,
                            topLeft = topLeft,
                            size = rectSize,
                            cornerRadius = corner
                        )
                        if (!isVideo && enabled) {
                            drawRoundRect(
                                color = photoBorderColor,
                                topLeft = topLeft,
                                size = rectSize,
                                cornerRadius = corner,
                                style = Stroke(width = 0.5.dp.toPx())
                            )
                        }
                    }
            )
        }
    }
}

data class WearPillSegmentOption(
    val label: String,
    val icon: ImageVector? = null,
    @param:DrawableRes @get:DrawableRes val drawableRes: Int? = null
)

/**
 * Compact Wear port of mobile's `PillSegmentedControl`: recessed `surfaceContainerHigh` capsule
 * with hairline `outlineVariant` border and GPU-translated `primary` active capsule.
 */
@Composable
fun WearPillSegmentedControl(
    options: List<WearPillSegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    minHeight: Dp = 42.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium
) {
    if (options.isEmpty()) return
    val clampedSelected = selectedIndex.coerceIn(0, options.lastIndex)
    val reducedMotion = rememberWearReducedMotion()
    val haptic = LocalHapticFeedback.current

    val indicatorIndex by animateFloatAsState(
        targetValue = clampedSelected.toFloat(),
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 200, easing = WearEaseOutCubic),
        label = "wearPillIndicatorIndex"
    )

    val containerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val containerBorder = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
    val outerModifier = if (transformation != null) {
        val painter = remember(transformation, containerBg, containerBorder) {
            transformation.createContainerPainter(
                painter = ColorPainter(containerBg),
                shape = CircleShape,
                border = containerBorder
            )
        }
        Modifier
            .graphicsLayer { with(transformation) { applyContainerTransformation() } }
            .then(modifier)
            .drawBehind { with(painter) { draw(size) } }
            .graphicsLayer {
                this.shape = CircleShape
                with(transformation) { applyContentTransformation() }
                clip = true
            }
            .padding(3.dp)
            .selectableGroup()
    } else {
        modifier
            .clip(CircleShape)
            .background(containerBg)
            .border(border = containerBorder, shape = CircleShape)
            .padding(3.dp)
            .selectableGroup()
    }

    BoxWithConstraints(
        modifier = outerModifier
    ) {
        val segmentWidth = maxWidth / options.size
        val segmentWidthPx = with(LocalDensity.current) { segmentWidth.toPx() }
        val indicatorColor = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = indicatorIndex * segmentWidthPx }
                    .clip(CircleShape)
                    .background(indicatorColor)
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == clampedSelected
                    val targetContentColor = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val contentColor by animateColorAsState(
                        targetValue = targetContentColor,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 120 else 160,
                            easing = WearEaseOutCubic
                        ),
                        label = "wearPillContentColor"
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressScale by rememberWearPressScale(interactionSource, targetScale = 0.96f)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = minHeight)
                            .clip(CircleShape)
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                role = role,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {
                                    if (!isSelected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelect(index)
                                    }
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (option.drawableRes != null) {
                                Icon(
                                    painter = painterResource(option.drawableRes),
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            } else if (option.icon != null) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                            Text(
                                text = option.label,
                                style = textStyle,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun wearEditorialTonalButtonColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    secondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.primary
)

@Composable
fun wearEditorialOutlinedButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    contentColor = MaterialTheme.colorScheme.onSurface,
    secondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun wearEditorialRadioColors(): RadioButtonColors = RadioButtonDefaults.radioButtonColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedSecondaryContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedControlColor = MaterialTheme.colorScheme.primary,
    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
    unselectedSecondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedControlColor = MaterialTheme.colorScheme.outline
)

@Composable
fun wearEditorialSwitchColors(): SwitchButtonColors = SwitchButtonDefaults.switchButtonColors(
    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    checkedSecondaryContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
    checkedIconColor = MaterialTheme.colorScheme.primary,
    checkedThumbColor = MaterialTheme.colorScheme.primaryContainer,
    checkedThumbIconColor = MaterialTheme.colorScheme.primary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedTrackBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    uncheckedContentColor = MaterialTheme.colorScheme.onSurface,
    uncheckedSecondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    uncheckedTrackBorderColor = MaterialTheme.colorScheme.outline
)
