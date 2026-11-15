package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlin.math.roundToInt

/**
 * Per-photo facial mouth anchor metadata (normalized image coordinates [0..1])
 * used to animate real photographic lower-lip / jaw articulation with ContentScale.Crop + Alignment.TopCenter.
 */
data class AvatarMouthSpec(
    val imgAspect: Float,
    val mouthX: Float,
    val mouthY: Float,
    val mouthW: Float
)

/**
 * Cybernetic Android Assistant Avatar supporting Detroit: Become Human style
 * Chloe (Female) and Connor (Male/Deep) character photos.
 * Automatically switches photo when the voice profile changes and animates
 * realistic talking jaw/lip movement synchronized with speech.
 */
object AndroidAvatarCatalog {
    val femaleDrawables = listOf(
        R.drawable.avatar_female_chloe,
        R.drawable.avatar_female_chloe_close,
        R.drawable.avatar_female_chloe_seated
    )

    val maleDrawables = listOf(
        R.drawable.avatar_male_connor_rk800,
        R.drawable.avatar_male_connor,
        R.drawable.avatar_male_connor_smile,
        R.drawable.avatar_male_connor_profile
    )

    private val mouthSpecs: Map<Int, AvatarMouthSpec> = mapOf(
        R.drawable.avatar_female_chloe to AvatarMouthSpec(imgAspect = 0.667f, mouthX = 0.49f, mouthY = 0.388f, mouthW = 0.135f),
        R.drawable.avatar_female_chloe_close to AvatarMouthSpec(imgAspect = 415f / 739f, mouthX = 0.54f, mouthY = 0.475f, mouthW = 0.22f),
        R.drawable.avatar_female_chloe_seated to AvatarMouthSpec(imgAspect = 415f / 739f, mouthX = 0.39f, mouthY = 0.228f, mouthW = 0.12f),
        R.drawable.avatar_male_connor_rk800 to AvatarMouthSpec(imgAspect = 1.0f, mouthX = 0.50f, mouthY = 0.455f, mouthW = 0.13f),
        R.drawable.avatar_male_connor to AvatarMouthSpec(imgAspect = 637f / 849f, mouthX = 0.59f, mouthY = 0.485f, mouthW = 0.16f),
        R.drawable.avatar_male_connor_smile to AvatarMouthSpec(imgAspect = 345f / 460f, mouthX = 0.58f, mouthY = 0.705f, mouthW = 0.26f),
        R.drawable.avatar_male_connor_profile to AvatarMouthSpec(imgAspect = 437f / 702f, mouthX = 0.47f, mouthY = 0.232f, mouthW = 0.085f)
    )

    fun getMouthSpec(drawableRes: Int): AvatarMouthSpec {
        return mouthSpecs[drawableRes] ?: AvatarMouthSpec(0.75f, 0.5f, 0.45f, 0.15f)
    }

    fun getDrawableForProfile(voiceProfile: String, index: Int = 0): Int {
        val isFemale = voiceProfile.equals("FEMALE", ignoreCase = true)
        val list = if (isFemale) femaleDrawables else maleDrawables
        val safeIdx = Math.floorMod(index, list.size)
        return list[safeIdx]
    }

    fun getPersonaName(voiceProfile: String): String {
        return if (voiceProfile.equals("FEMALE", ignoreCase = true)) {
            "Chloe Android"
        } else if (voiceProfile.equals("DEEP", ignoreCase = true)) {
            "Connor RK800 (Deep)"
        } else {
            "Connor RK800"
        }
    }
}

private fun ovalPatchShape(left: Float, top: Float, width: Float, height: Float): Shape {
    return GenericShape { _, _ ->
        addOval(Rect(left = left, top = top, right = left + width, bottom = top + height))
    }
}

@Composable
private fun ArticulatedPortraitWithLipSync(
    drawableRes: Int,
    contentDescription: String,
    isSpeaking: Boolean,
    mouthOpenFraction: Float,
    modifier: Modifier = Modifier,
    customAvatarBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val spec = if (customAvatarBitmap != null) {
        AvatarMouthSpec(
            imgAspect = (customAvatarBitmap.width.toFloat() / customAvatarBitmap.height.coerceAtLeast(1).toFloat()).coerceIn(0.45f, 1.5f),
            mouthX = 0.50f,
            mouthY = 0.475f,
            mouthW = 0.16f
        )
    } else {
        AndroidAvatarCatalog.getMouthSpec(drawableRes)
    }

    BoxWithConstraints(modifier = modifier) {
        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val boxAspect = boxW / boxH

        val drawnW: Float
        val drawnH: Float
        val offsetX: Float
        val offsetY = 0f // Matches Alignment.TopCenter

        if (boxAspect > spec.imgAspect) {
            drawnW = boxW
            drawnH = boxW / spec.imgAspect
            offsetX = 0f
        } else {
            drawnH = boxH
            drawnW = boxH * spec.imgAspect
            offsetX = (boxW - drawnW) * 0.5f
        }

        val mouthCenterX = offsetX + spec.mouthX * drawnW
        val mouthCenterY = offsetY + spec.mouthY * drawnH
        val mouthWidthPx = (spec.mouthW * drawnW).coerceAtLeast(12f)
        val maxDropPx = (mouthWidthPx * 0.16f).coerceIn(3f, 14f)
        val jawDropPx = if (isSpeaking) maxDropPx * mouthOpenFraction.coerceIn(0f, 1f) else 0f

        // Base crisp portrait
        if (customAvatarBitmap != null) {
            Image(
                bitmap = customAvatarBitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = drawableRes),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isSpeaking && jawDropPx > 0.35f) {
            val cavityW = mouthWidthPx * 0.78f
            val cavityH = (jawDropPx * 1.15f + mouthWidthPx * 0.06f).coerceAtLeast(2f)
            val cavityLeft = mouthCenterX - cavityW * 0.5f
            val cavityTop = mouthCenterY - cavityH * 0.25f

            // Subtle dark oral interior revealed seamlessly behind the descending lower jaw
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xE8160709),
                            Color(0xCC240B0D),
                            Color.Transparent
                        ),
                        center = Offset(mouthCenterX, cavityTop + cavityH * 0.52f),
                        radius = (cavityW * 0.6f).coerceAtLeast(2f)
                    ),
                    topLeft = Offset(cavityLeft, cavityTop),
                    size = Size(cavityW, cavityH)
                )
            }

            // Photographic lower-lip + chin patch from the exact same portrait moving vertically
            val jawW = mouthWidthPx * 1.48f
            val jawH = mouthWidthPx * 1.05f
            val jawLeft = mouthCenterX - jawW * 0.5f
            val jawTop = mouthCenterY
            val jawShape = remember(jawLeft, jawTop, jawW, jawH) {
                ovalPatchShape(jawLeft, jawTop, jawW, jawH)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, jawDropPx.roundToInt()) }
                    .clip(jawShape)
            ) {
                if (customAvatarBitmap != null) {
                    Image(
                        bitmap = customAvatarBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = drawableRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Passport Photo Sized Android Assistant Card (Detroit: Become Human CyberLife Passport Style).
 * Perfectly fills the prominent central stage area in both AUTO and MANUAL mode.
 * Features realistic photographic lower-jaw/lip talking animation synchronized with speech,
 * glowing Detroit temple LED ring, and quick photo/voice switching controls.
 */
@Composable
fun PassportAndroidAvatarCard(
    voiceProfile: String,
    isListening: Boolean,
    isSpeaking: Boolean,
    rmsDb: Float,
    modifier: Modifier = Modifier,
    photoIndex: Int = 0,
    autoCutProgress: Float = 0f,
    customAvatarBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    useCustomPortraitAvatar: Boolean = false,
    onPhotoCycle: () -> Unit = {},
    onVoiceProfileToggle: () -> Unit = {},
    onCardClick: () -> Unit = {}
) {
    val isFemale = voiceProfile.equals("FEMALE", ignoreCase = true)
    val maxPhotos = if (isFemale) AndroidAvatarCatalog.femaleDrawables.size else AndroidAvatarCatalog.maleDrawables.size
    val showingCustom = customAvatarBitmap != null && useCustomPortraitAvatar
    val activeDrawableRes = AndroidAvatarCatalog.getDrawableForProfile(voiceProfile, photoIndex)
    val personaLabel = if (showingCustom) "Custom Portrait" else AndroidAvatarCatalog.getPersonaName(voiceProfile)

    val infiniteTransition = rememberInfiniteTransition(label = "passport_avatar_anim")
    val ledRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking || isListening) 2200 else 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "passport_led_rotation"
    )

    val idleBreathing by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.012f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_idle_breathing"
    )

    // Multi-harmonic speech cadence so lip-sync feels organic rather than uniform metronome
    val mouthWaveA by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 148, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mouth_wave_a"
    )
    val mouthWaveB by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 223, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mouth_wave_b"
    )
    val baseWave = (mouthWaveA * 0.68f + mouthWaveB * 0.32f).coerceIn(0f, 1f)
    val acousticGain = if (rmsDb > 0.5f) (rmsDb / 8f).coerceIn(0.4f, 1.35f) else 1f
    val mouthOpenFraction = (baseWave * acousticGain).coerceIn(0.08f, 1f)

    val speechBob by infiniteTransition.animateFloat(
        initialValue = -0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 190, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_speech_bob"
    )

    val speechPulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.014f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 210, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_speech_pulse"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 210, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_wave_phase"
    )

    val ledColor = when {
        isSpeaking -> Color(0xFFFFB300) // Amber LED when speaking (Detroit Become Human style)
        isListening -> Color(0xFF00E676) // Emerald/Cyan when listening
        else -> Color(0xFF00E5FF) // Blue/Cyan idle LED
    }

    val portraitScale = if (isSpeaking) speechPulse else idleBreathing
    val speechBobOffset = if (isSpeaking) speechBob.dp else 0.dp
    val nextVoiceChipText = when {
        voiceProfile.equals("FEMALE", ignoreCase = true) -> "পুরুষ কণ্ঠ ⇄"
        voiceProfile.equals("MALE", ignoreCase = true) -> "ডিপ কণ্ঠ ⇄"
        else -> "নারী কণ্ঠ ⇄"
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF081220)),
        border = BorderStroke(
            width = if (isSpeaking || isListening) 2.2.dp else 1.2.dp,
            brush = Brush.linearGradient(
                listOf(
                    ledColor.copy(alpha = if (isSpeaking) 0.95f else 0.65f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    ledColor.copy(alpha = 0.35f)
                )
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .semantics { contentDescription = "Hey Shuvo Android Avatar $personaLabel" }
            .testTag("passport_android_avatar_card")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Completely Unobstructed Passport Portrait Image Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF050B14))
                    .clickable { onCardClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(portraitScale)
                        .offset(y = speechBobOffset),
                    contentAlignment = Alignment.TopCenter
                ) {
                    AnimatedContent(
                        targetState = if (showingCustom) "custom_${System.identityHashCode(customAvatarBitmap)}" else "res_$activeDrawableRes",
                        transitionSpec = {
                            fadeIn(animationSpec = tween(320)) togetherWith fadeOut(animationSpec = tween(320))
                        },
                        label = "avatar_photo_crossfade"
                    ) { targetKey ->
                        val isCustomTarget = targetKey.startsWith("custom_")
                        val targetDrawable = if (isCustomTarget) {
                            activeDrawableRes
                        } else {
                            targetKey.removePrefix("res_").toIntOrNull() ?: activeDrawableRes
                        }
                        ArticulatedPortraitWithLipSync(
                            drawableRes = targetDrawable,
                            customAvatarBitmap = if (isCustomTarget) customAvatarBitmap else null,
                            contentDescription = "$personaLabel Full Portrait",
                            isSpeaking = isSpeaking,
                            mouthOpenFraction = mouthOpenFraction,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Subtle acoustic edge glow while speaking
                if (isSpeaking) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(20.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        ledColor.copy(alpha = 0.14f + 0.10f * wavePhase)
                                    )
                                )
                            )
                    )
                }

                // Iconic Detroit Temple LED Ring (Top Right corner, outside face center)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val r = size.width / 2f * 0.84f
                        drawCircle(
                            color = ledColor.copy(alpha = if (isSpeaking) 0.78f else 0.35f),
                            radius = r,
                            center = center
                        )
                        rotate(degrees = ledRotation, pivot = center) {
                            drawArc(
                                color = ledColor,
                                startAngle = 0f,
                                sweepAngle = 250f,
                                useCenter = false,
                                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                }

                // Auto-cut countdown indicator along bottom edge of portrait frame
                if (autoCutProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(autoCutProgress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color(0xFF00E676))
                    )
                }
            }

            // 2. Clean Dedicated Controls & Status Strip BELOW the Photo
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1424))
                    .border(
                        width = 0.8.dp,
                        color = ledColor.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ledColor)
                        )
                        Text(
                            text = when {
                                isSpeaking -> "কথা বলছে..."
                                isListening -> "শুনছে..."
                                else -> personaLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    // Subtle live acoustic equalizer bars when speaking/listening
                    if (isSpeaking || isListening) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val heights = listOf(0.45f, 0.9f, 0.65f, 1.0f, 0.55f)
                            heights.forEachIndexed { idx, base ->
                                val barScale = if (idx % 2 == 0) wavePhase else (1.25f - wavePhase)
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.dp, height = (12f * base * barScale).coerceIn(4f, 14f).dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(ledColor)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF12233B))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .clickable { onPhotoCycle() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("avatar_photo_cycle_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        val safePhotoPos = Math.floorMod(photoIndex, maxPhotos) + 1
                        val photoLabelText = if (showingCustom) {
                            "ছবি 📷 কাস্টম"
                        } else {
                            val totalSlots = if (customAvatarBitmap != null) maxPhotos + 1 else maxPhotos
                            "ছবি 📷 $safePhotoPos/$totalSlots"
                        }
                        Text(
                            text = photoLabelText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF12233B))
                            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .clickable { onVoiceProfileToggle() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("avatar_voice_toggle_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = nextVoiceChipText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CyberAndroidAvatar(
    voiceProfile: String,
    isListening: Boolean,
    isSpeaking: Boolean,
    rmsDb: Float,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 186.dp,
    autoCutProgress: Float = 0f,
    photoIndex: Int = 0
) {
    val activeDrawableRes = AndroidAvatarCatalog.getDrawableForProfile(voiceProfile, photoIndex)
    val personaLabel = AndroidAvatarCatalog.getPersonaName(voiceProfile)

    val infiniteTransition = rememberInfiniteTransition(label = "cyber_avatar_pulse")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 320 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_phase"
    )
    val ledRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking || isListening) 2400 else 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "led_rotation"
    )

    val mouthWaveA by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 152, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cyber_mouth_a"
    )
    val mouthWaveB by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 229, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cyber_mouth_b"
    )
    val mouthOpenFraction = (mouthWaveA * 0.68f + mouthWaveB * 0.32f).coerceIn(0f, 1f)

    val jawCadence by infiniteTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 185, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jaw_cadence"
    )

    val ledColor = when {
        isSpeaking -> Color(0xFFFFB300)
        isListening -> Color(0xFF00E676)
        else -> Color(0xFF00E5FF)
    }

    val dynamicBoost = if (isListening) (rmsDb / 15f).coerceIn(0f, 0.16f) else if (isSpeaking) 0.05f else 0f
    val effectiveScale = (if (isSpeaking) pulsePhase else 1f) + dynamicBoost
    val jawBobOffset = if (isSpeaking) jawCadence.dp else 0.dp

    Box(
        modifier = modifier
            .size(sizeDp)
            .semantics { contentDescription = "Hey Shuvo Android Avatar $personaLabel" }
            .testTag("cyber_android_avatar"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val baseRadius = (w.coerceAtMost(h) / 2f) * 0.88f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ledColor.copy(alpha = 0.35f + dynamicBoost * 0.5f),
                        ledColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.25f
                ),
                radius = baseRadius * 1.25f,
                center = center
            )

            rotate(degrees = ledRotation, pivot = center) {
                drawArc(
                    color = ledColor.copy(alpha = 0.5f),
                    startAngle = 10f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - baseRadius * 1.05f, center.y - baseRadius * 1.05f),
                    size = Size(baseRadius * 2.1f, baseRadius * 2.1f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = ledColor.copy(alpha = 0.5f),
                    startAngle = 190f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - baseRadius * 1.05f, center.y - baseRadius * 1.05f),
                    size = Size(baseRadius * 2.1f, baseRadius * 2.1f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            if (isListening && autoCutProgress > 0.03f) {
                val cutR = baseRadius * 1.12f
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
        }

        Box(
            modifier = Modifier
                .fillMaxSize(0.78f)
                .scale(effectiveScale)
                .shadow(16.dp, CircleShape)
                .clip(CircleShape)
                .border(
                    width = if (isSpeaking || isListening) 3.dp else 2.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            ledColor,
                            MaterialTheme.colorScheme.primary,
                            ledColor
                        )
                    ),
                    shape = CircleShape
                )
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = jawBobOffset),
                contentAlignment = Alignment.TopCenter
            ) {
                AnimatedContent(
                    targetState = activeDrawableRes,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(350))
                    },
                    label = "avatar_photo_crossfade"
                ) { targetRes ->
                    ArticulatedPortraitWithLipSync(
                        drawableRes = targetRes,
                        contentDescription = "$personaLabel Portrait",
                        isSpeaking = isSpeaking,
                        mouthOpenFraction = mouthOpenFraction,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = (sizeDp * 0.16f), top = (sizeDp * 0.14f))
                .size(sizeDp * 0.14f)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .border(width = 2.dp, color = ledColor, shape = CircleShape)
                .background(ledColor.copy(alpha = if (isSpeaking) 0.85f else 0.45f))
        )
    }
}
