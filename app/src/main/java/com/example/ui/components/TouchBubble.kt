package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Provides unmistakable tactile touch feedback on any button, pill, or orb:
 * 1) Bouncy spring press scale so the user feels the button depress and rebound.
 * 2) Expanding luminous bubble ring + soft glow centered at the exact finger tap point,
 *    working reliably even inside Material 3 `Button` or `IconButton` children via `Initial` pass!
 */
fun Modifier.bubbleTouchEffect(
    bubbleColor: Color = Color(0xFF00E5FF),
    pressedScale: Float = 0.92f,
    enableHaptic: Boolean = true
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapOffset by remember { mutableStateOf(Offset.Unspecified) }
    val bubbleProgress = remember { Animatable(1f) }

    LaunchedEffect(tapCount) {
        if (tapCount > 0) {
            bubbleProgress.snapTo(0f)
            bubbleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 420)
            )
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bubbleTouchScale"
    )

    this
        .pointerInput(enableHaptic) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val firstDown = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                    if (firstDown != null) {
                        isPressed = true
                        lastTapOffset = firstDown.position
                        tapCount++
                        if (enableHaptic) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    } else if (event.changes.none { it.pressed }) {
                        isPressed = false
                    }
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            val p = bubbleProgress.value
            if (p < 0.99f) {
                val centerPt = if (lastTapOffset != Offset.Unspecified) {
                    Offset(
                        x = lastTapOffset.x.coerceIn(0f, size.width),
                        y = lastTapOffset.y.coerceIn(0f, size.height)
                    )
                } else {
                    Offset(size.width / 2f, size.height / 2f)
                }
                val maxR = maxOf(size.width, size.height) * 0.72f
                val currentR = maxR * (0.20f + 0.85f * p)
                val alpha = (1f - p).coerceIn(0f, 1f)
                drawCircle(
                    color = bubbleColor.copy(alpha = 0.26f * alpha),
                    radius = currentR,
                    center = centerPt
                )
                drawCircle(
                    color = bubbleColor.copy(alpha = 0.88f * alpha),
                    radius = currentR,
                    center = centerPt,
                    style = Stroke(width = 2.6f * density)
                )
            }
        }
}
