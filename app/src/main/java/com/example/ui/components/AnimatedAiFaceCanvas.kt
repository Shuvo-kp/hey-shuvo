package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.preferences.AiPersonaFace
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AnimatedAiFaceCanvas(
    personaFace: AiPersonaFace,
    isListening: Boolean,
    isSpeaking: Boolean,
    rmsDb: Float,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 152.dp,
    autoCutProgress: Float = 0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hud_face_anim")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isListening || isSpeaking) 4200 else 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 380 else 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_phase"
    )
    val blinkCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blink_cycle"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val accentColor = if (isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary

    Canvas(
        modifier = modifier
            .size(sizeDp)
            .semantics { contentDescription = "Hey Shuvo animated ${personaFace.label} avatar" }
            .testTag("ai_animated_face")
    ) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val baseRadius = (w.coerceAtMost(h) / 2f) * 0.82f
        val dynamicBoost = if (isListening) (rmsDb / 14f).coerceIn(0f, 0.25f) else if (isSpeaking) (pulsePhase - 0.9f) else 0f

        // Ambient glow halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.34f + dynamicBoost * 0.4f),
                    accentColor.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = center,
                radius = baseRadius * 1.18f
            ),
            radius = baseRadius * 1.18f,
            center = center
        )

        // 1.4-second Auto-Cut Countdown Ring when pause is detected
        if (isListening && autoCutProgress > 0.03f) {
            val cutR = baseRadius * 1.09f
            drawArc(
                color = Color(0xFFFACC15),
                startAngle = -90f,
                sweepAngle = 360f * autoCutProgress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(center.x - cutR, center.y - cutR),
                size = Size(cutR * 2f, cutR * 2f),
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        when (personaFace) {
            AiPersonaFace.ORBITAL_HUD -> {
                val radius = baseRadius
                val headCenterY = center.y - radius * 0.42f
                val headRadius = radius * 0.27f
                val torsoTopY = headCenterY + headRadius * 1.18f
                val torsoBottomY = center.y + radius * 0.86f
                val shoulderHalfW = radius * 0.64f
                val waistHalfW = radius * 0.42f

                val torsoPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x - shoulderHalfW, torsoTopY + radius * 0.14f)
                    quadraticTo(center.x, torsoTopY - radius * 0.08f, center.x + shoulderHalfW, torsoTopY + radius * 0.14f)
                    lineTo(center.x + waistHalfW, torsoBottomY)
                    lineTo(center.x - waistHalfW, torsoBottomY)
                    close()
                }
                drawPath(
                    path = torsoPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.28f),
                            accentColor.copy(alpha = 0.14f)
                        )
                    )
                )
                drawPath(
                    path = torsoPath,
                    color = primaryColor.copy(alpha = 0.82f),
                    style = Stroke(width = 2.4.dp.toPx())
                )

                // Robotic Arms
                drawLine(
                    color = primaryColor.copy(alpha = 0.75f),
                    start = Offset(center.x - shoulderHalfW, torsoTopY + radius * 0.16f),
                    end = Offset(center.x - shoulderHalfW * 1.12f, torsoBottomY - radius * 0.06f),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = primaryColor.copy(alpha = 0.75f),
                    start = Offset(center.x + shoulderHalfW, torsoTopY + radius * 0.16f),
                    end = Offset(center.x + shoulderHalfW * 1.12f, torsoBottomY - radius * 0.06f),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Neck
                drawLine(
                    color = primaryColor.copy(alpha = 0.85f),
                    start = Offset(center.x, headCenterY + headRadius * 0.92f),
                    end = Offset(center.x, torsoTopY + radius * 0.05f),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Humanoid Head
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.34f),
                            accentColor.copy(alpha = 0.12f)
                        ),
                        center = Offset(center.x, headCenterY),
                        radius = headRadius
                    ),
                    radius = headRadius,
                    center = Offset(center.x, headCenterY)
                )
                drawCircle(
                    color = primaryColor,
                    radius = headRadius,
                    center = Offset(center.x, headCenterY),
                    style = Stroke(width = 2.4.dp.toPx())
                )

                // Glowing Neural / Circuit Veins + Flowing Free Electrons (মুক্ত ইলেকট্রন)
                val electronPhase = (orbitAngle / 360f)
                val veinPaths = listOf(
                    listOf(
                        Offset(center.x, headCenterY + headRadius * 0.4f),
                        Offset(center.x, torsoTopY + radius * 0.25f),
                        Offset(center.x, torsoBottomY - radius * 0.12f)
                    ),
                    listOf(
                        Offset(center.x, torsoTopY + radius * 0.22f),
                        Offset(center.x - shoulderHalfW * 0.75f, torsoTopY + radius * 0.20f),
                        Offset(center.x - shoulderHalfW * 1.08f, torsoBottomY - radius * 0.10f)
                    ),
                    listOf(
                        Offset(center.x, torsoTopY + radius * 0.22f),
                        Offset(center.x + shoulderHalfW * 0.75f, torsoTopY + radius * 0.20f),
                        Offset(center.x + shoulderHalfW * 1.08f, torsoBottomY - radius * 0.10f)
                    ),
                    listOf(
                        Offset(center.x, torsoTopY + radius * 0.42f),
                        Offset(center.x - waistHalfW * 0.75f, torsoBottomY - radius * 0.18f)
                    ),
                    listOf(
                        Offset(center.x, torsoTopY + radius * 0.42f),
                        Offset(center.x + waistHalfW * 0.75f, torsoBottomY - radius * 0.18f)
                    )
                )

                veinPaths.forEachIndexed { vIdx, pts ->
                    for (i in 0 until pts.size - 1) {
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        drawLine(
                            color = accentColor.copy(alpha = 0.55f),
                            start = p1,
                            end = p2,
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        for (e in 0..1) {
                            val t = (electronPhase + vIdx * 0.19f + e * 0.5f) % 1f
                            val ex = p1.x + (p2.x - p1.x) * t
                            val ey = p1.y + (p2.y - p1.y) * t
                            drawCircle(
                                color = Color.White.copy(alpha = 0.92f),
                                radius = 2.6.dp.toPx(),
                                center = Offset(ex, ey)
                            )
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.55f),
                                radius = 5.2.dp.toPx(),
                                center = Offset(ex, ey)
                            )
                        }
                    }
                }

                // Chest Arc Reactor Core
                val coreY = torsoTopY + radius * 0.30f
                drawCircle(
                    color = accentColor.copy(alpha = 0.85f),
                    radius = radius * 0.11f * pulsePhase,
                    center = Offset(center.x, coreY)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = radius * 0.05f,
                    center = Offset(center.x, coreY)
                )
                // Outer segmented telemetry ring
                rotate(degrees = orbitAngle, pivot = center) {
                    for (seg in 0 until 6) {
                        drawArc(
                            color = primaryColor.copy(alpha = 0.85f),
                            startAngle = seg * 60f + 8f,
                            sweepAngle = 38f,
                            useCenter = false,
                            topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                            size = Size(baseRadius * 2f, baseRadius * 2f),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                // Counter-rotating inner ring
                val innerR = baseRadius * 0.72f
                rotate(degrees = -orbitAngle * 1.4f, pivot = center) {
                    drawArc(
                        color = accentColor.copy(alpha = 0.9f),
                        startAngle = 20f,
                        sweepAngle = 220f,
                        useCenter = false,
                        topLeft = Offset(center.x - innerR, center.y - innerR),
                        size = Size(innerR * 2f, innerR * 2f),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                // Expressive eyes & speaking waveform mouth
                val eyeOpenFactor = if (blinkCycle in 0.92f..0.97f) 0.15f else 1f
                val eyeRadiusX = baseRadius * 0.11f
                val eyeRadiusY = baseRadius * 0.13f * eyeOpenFactor
                val eyeY = center.y - baseRadius * 0.14f
                drawOval(
                    color = primaryColor,
                    topLeft = Offset(center.x - baseRadius * 0.28f - eyeRadiusX, eyeY - eyeRadiusY),
                    size = Size(eyeRadiusX * 2f, (eyeRadiusY * 2f).coerceAtLeast(2f))
                )
                drawOval(
                    color = primaryColor,
                    topLeft = Offset(center.x + baseRadius * 0.28f - eyeRadiusX, eyeY - eyeRadiusY),
                    size = Size(eyeRadiusX * 2f, (eyeRadiusY * 2f).coerceAtLeast(2f))
                )

                // Speaking mouth bars
                val barCount = 7
                val mouthWidth = baseRadius * 0.68f
                val startX = center.x - mouthWidth / 2f
                val stepX = mouthWidth / (barCount - 1)
                for (i in 0 until barCount) {
                    val wave = if (isSpeaking || isListening) {
                        val phase = (orbitAngle * 0.12f) + i * 0.85f
                        (0.3f + 0.7f * kotlin.math.abs(sin(phase.toDouble())).toFloat()) * (1f + dynamicBoost)
                    } else {
                        0.22f
                    }
                    val barH = baseRadius * 0.34f * wave
                    val bx = startX + i * stepX
                    val by = center.y + baseRadius * 0.24f
                    drawLine(
                        color = if (isSpeaking) accentColor else primaryColor,
                        start = Offset(bx, by - barH / 2f),
                        end = Offset(bx, by + barH / 2f),
                        strokeWidth = 3.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            AiPersonaFace.PULSE_MATRIX -> {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.5f),
                    radius = baseRadius * 0.95f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                val cols = 9
                val span = baseRadius * 1.3f
                val startX = center.x - span / 2f
                val dx = span / (cols - 1)
                for (i in 0 until cols) {
                    val norm = 1f - kotlin.math.abs(i - 4) / 5f
                    val amp = if (isSpeaking || isListening) {
                        norm * (0.4f + 0.6f * kotlin.math.abs(sin((orbitAngle * 0.1f + i).toDouble())).toFloat())
                    } else {
                        norm * 0.28f
                    }
                    val bh = baseRadius * 0.9f * amp
                    drawLine(
                        color = if (i % 2 == 0) primaryColor else accentColor,
                        start = Offset(startX + i * dx, center.y - bh / 2f),
                        end = Offset(startX + i * dx, center.y + bh / 2f),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            AiPersonaFace.SENTINEL_ARC -> {
                rotate(degrees = orbitAngle * 0.5f, pivot = center) {
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.7f),
                        radius = baseRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                val visorW = baseRadius * 1.25f
                val visorH = baseRadius * 0.42f
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.18f),
                    topLeft = Offset(center.x - visorW / 2f, center.y - visorH / 2f),
                    size = Size(visorW, visorH),
                    cornerRadius = CornerRadius(visorH / 2f, visorH / 2f)
                )
                val scannerOffset = sin((orbitAngle * PI / 180.0)).toFloat() * (visorW * 0.34f)
                drawCircle(
                    color = accentColor,
                    radius = (visorH * 0.34f) * (if (isSpeaking) pulsePhase else 1f),
                    center = Offset(center.x + scannerOffset, center.y)
                )
            }
        }
    }
}

@Composable
fun FloatingAssistantBubble(
    isListening: Boolean,
    isSpeaking: Boolean,
    onBubbleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(58.dp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            )
            .border(
                width = 2.dp,
                color = if (isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .clickable { onBubbleTap() }
            .testTag("floating_assistant_bubble"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isSpeaking || isListening) Icons.Filled.GraphicEq else Icons.Filled.Mic,
            contentDescription = "Quick Hey Shuvo Floating Voice Trigger",
            tint = if (isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
    }
}
