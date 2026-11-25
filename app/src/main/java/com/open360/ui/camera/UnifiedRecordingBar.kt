package com.open360.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.core.ui.Insta360Icons
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.PillSegmentOption
import com.open360.ui.theme.PillSegmentedControl
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.rememberPressScale
import com.open360.ui.theme.rememberRecordingPulseAlpha
import com.open360.ui.theme.rememberReducedMotion
import com.open360.ui.theme.statusColors

/**
 * Sticky unified capture bar docked directly above the bottom navigation bar across every main tab.
 * Places capture mode selection and contextual status on the left column with a large, scaled
 * shutter button anchored full-height on the right side of the bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedRecordingBar(
    currentMode: CaptureMode,
    isRecording: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    isTakingPhoto: Boolean,
    durationSeconds: Long,
    isConnected: Boolean,
    isAsleep: Boolean,
    isMovingLink: Boolean,
    recordingModeName: String?,
    cameraMode: CameraMode?,
    onModeSelected: (CaptureMode) -> Unit,
    onCameraModeSelected: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val modeLocked = isRecording || isStarting || isStopping || isTakingPhoto
    val shownMode = cameraMode?.takeIf { it.isPhoto == (currentMode == CaptureMode.PHOTO) }
    val activeModeName = recordingModeName ?: shownMode?.label ?: currentMode.displayName
    val shutterEnabled = isConnected && !isAsleep && !isMovingLink
    val surfaceBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val pulseAlpha = rememberRecordingPulseAlpha(active = isRecording)
    val showSubModeStrip = cameraMode != null

    val recordingAlpha by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = tween(if (isRecording) 160 else 110, easing = EaseOutCubic),
        label = "barRecordingAlpha"
    )

    Surface(
        color = surfaceBg,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (showSubModeStrip) 2.dp else 0.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        PillSegmentedControl(
                            options = listOf(
                                PillSegmentOption(
                                    label = "Video",
                                    drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.VIDEO)
                                ),
                                PillSegmentOption(
                                    label = "Photo",
                                    drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.PHOTO)
                                )
                            ),
                            selectedIndex = if (currentMode == CaptureMode.PHOTO) 1 else 0,
                            onSelect = { index ->
                                onModeSelected(if (index == 1) CaptureMode.PHOTO else CaptureMode.VIDEO)
                            },
                            enabled = shutterEnabled && !modeLocked,
                            role = Role.RadioButton,
                            minHeight = 38.dp,
                            textStyle = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = 1f - recordingAlpha }
                                .then(if (isRecording) Modifier.clearAndSetSemantics {} else Modifier)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = recordingAlpha }
                                .then(if (isRecording) Modifier else Modifier.clearAndSetSemantics {})
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .graphicsLayer { alpha = pulseAlpha.value }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.statusColors.recording)
                            )
                            Text(
                                text = CameraStatusText.formatRecordingDuration(durationSeconds),
                                style = MaterialTheme.typography.titleLarge.merge(TabularFigures),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.statusColors.recording,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = activeModeName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showSubModeStrip,
                        enter = if (reducedMotion) {
                            fadeIn(tween(140, easing = EaseOutCubic))
                        } else {
                            fadeIn(tween(160, easing = EaseOutCubic)) +
                                expandVertically(tween(180, easing = EaseOutCubic))
                        },
                        exit = if (reducedMotion) {
                            fadeOut(tween(110, easing = EaseOutCubic))
                        } else {
                            fadeOut(tween(110, easing = EaseOutCubic)) +
                                shrinkVertically(tween(150, easing = EaseOutCubic))
                        }
                    ) {
                        val modesScrollState = rememberScrollState()
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(modesScrollState, enabled = !isRecording),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CameraMode.group(photo = currentMode == CaptureMode.PHOTO).forEach { mode ->
                                    val isSelected = mode == shownMode
                                    val inView = remember { BringIntoViewRequester() }
                                    LaunchedEffect(isSelected) { if (isSelected) inView.bringIntoView() }
                                    val chipInteraction = remember { MutableInteractionSource() }
                                    val chipScale by rememberPressScale(chipInteraction, targetScale = 0.97f)
                                    val chipEnabled = shutterEnabled && !modeLocked

                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .bringIntoViewRequester(inView)
                                            .selectable(
                                                selected = isSelected,
                                                enabled = chipEnabled,
                                                role = Role.RadioButton,
                                                interactionSource = chipInteraction,
                                                indication = null,
                                                onClick = { onCameraModeSelected(mode) }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                            contentColor = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            border = if (isSelected) {
                                                null
                                            } else {
                                                BorderStroke(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                                )
                                            },
                                            modifier = Modifier
                                                .heightIn(min = 34.dp)
                                                .graphicsLayer {
                                                    scaleX = chipScale
                                                    scaleY = chipScale
                                                    alpha = if ((modeLocked || !shutterEnabled) && !isSelected) 0.45f else 1f
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(Insta360Icons.modeDrawableRes(mode)),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = mode.label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (modesScrollState.canScrollBackward) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .width(20.dp)
                                        .height(36.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(surfaceBg, Color.Transparent)
                                            )
                                        )
                                )
                            }
                            if (modesScrollState.canScrollForward) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(20.dp)
                                        .height(36.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(Color.Transparent, surfaceBg)
                                            )
                                        )
                                )
                            }
                        }
                    }
                }

                TactileShutterButton(
                    currentMode = currentMode,
                    isRecording = isRecording,
                    isStarting = isStarting || isTakingPhoto,
                    isStopping = isStopping,
                    enabled = shutterEnabled,
                    size = 68.dp,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShutterClick()
                    }
                )
            }
        }
    }
}
