package com.open360.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class PillSegmentOption(
    val label: String,
    val icon: ImageVector? = null,
    @param:DrawableRes @get:DrawableRes val drawableRes: Int? = null
)

/**
 * Recessed capsule segmented control with a GPU-translated active indicator pill.
 * Uses `EaseOutCubic` (200ms) and snaps cleanly when reduced motion is enabled.
 */
@Composable
fun PillSegmentedControl(
    options: List<PillSegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    minHeight: Dp = 42.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge
) {
    if (options.isEmpty()) return
    val clampedSelected = selectedIndex.coerceIn(0, options.lastIndex)
    val reducedMotion = rememberReducedMotion()
    val haptic = LocalHapticFeedback.current

    val indicatorIndex by animateFloatAsState(
        targetValue = clampedSelected.toFloat(),
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 200, easing = EaseOutCubic),
        label = "pillIndicatorIndex"
    )

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = CircleShape
            )
            .padding(4.dp)
            .selectableGroup()
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
                            easing = EaseOutCubic
                        ),
                        label = "pillContentColor"
                    )
                    val interactionSource = remember { MutableInteractionSource() }

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
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (option.drawableRes != null) {
                                Icon(
                                    painter = painterResource(option.drawableRes),
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else if (option.icon != null) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
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
