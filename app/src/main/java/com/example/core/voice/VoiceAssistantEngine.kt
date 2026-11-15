package com.example.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "HeyShuvoVoice"

enum class VoiceCaptureBackend {
    SYSTEM_SPEECH,
    DIRECT_AI_AUDIO
}

data class SpeechRecognitionState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasDetectedVoiceActivity: Boolean = false,
    val activeBackend: VoiceCaptureBackend = VoiceCaptureBackend.DIRECT_AI_AUDIO,
    val recordingSeconds: Int = 0,
    val autoCutProgress: Float = 0f,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val confidence: Float = 0f,
    val rmsDb: Float = 0f,
    val errorMessage: String? = null,
    val isContinuousAuto: Boolean = false,
    val isMicZeroSignal: Boolean = false
)

/**
 * Adaptive Voice Activity Detector (VAD) for 16-bit signed PCM audio.
 *
 * Calibrated for quiet phone microphones, fluctuating browser/WebRTC microphones,
 * and post-speech Automatic Gain Control (AGC) hiss.
 */
class AdaptiveVoiceActivityDetector(
    private val pauseThresholdMs: Long = 740L,
    private val noSpeechTimeoutMs: Long = 8500L,
    private val maxSpeechSpanMs: Long = 12500L,
    private val maxUtteranceMs: Long = 17500L,
    initialAmbientFloorRms: Double = 65.0,
    @Volatile var listenBackgroundVoices: Boolean = false
) {
    data class FrameDecision(
        val hasSpoken: Boolean,
        val isCurrentlySpeaking: Boolean,
        val silenceAfterSpeechMs: Long,
        val autoCutProgress: Float,
        val shouldAutoStop: Boolean,
        val timedOutWithoutSpeech: Boolean,
        val normalizedMeterLevel: Float
    )

    var noiseFloorRms: Double = initialAmbientFloorRms.coerceIn(24.0, 2200.0)
        private set
    var peakSpeechRms: Double = 0.0
        private set
    var hasDetectedSpeech: Boolean = false
        private set

    private val rmsWindow = ArrayDeque<Double>(28)
    private val steadySpeechWindow = ArrayDeque<Double>(24)
    private var nonZeroFrameCount = 0
    private var consecutiveSpeechFrames = 0
    private var postSpeechDropFrames = 0
    private var speechStartTimestampMs = -1L
    private var lastSpeechTimestampMs = 0L

    fun resetForNextUtterance() {
        peakSpeechRms = 0.0
        hasDetectedSpeech = false
        steadySpeechWindow.clear()
        consecutiveSpeechFrames = 0
        postSpeechDropFrames = 0
        speechStartTimestampMs = -1L
        lastSpeechTimestampMs = 0L
    }

    fun processChunk(rms: Double, elapsedMs: Long): FrameDecision {
        val isInitialNearSilentWarmup = !hasDetectedSpeech && nonZeroFrameCount == 0 && elapsedMs <= 260L && rms < 8.0
        if ((rms < 4.5 && !hasDetectedSpeech) || isInitialNearSilentWarmup) {
            val timedOutDigitalZero = elapsedMs >= noSpeechTimeoutMs
            return FrameDecision(
                hasSpoken = false,
                isCurrentlySpeaking = false,
                silenceAfterSpeechMs = 0L,
                autoCutProgress = 0f,
                shouldAutoStop = timedOutDigitalZero || elapsedMs >= maxUtteranceMs,
                timedOutWithoutSpeech = timedOutDigitalZero,
                normalizedMeterLevel = 0.3f
            )
        }

        nonZeroFrameCount++
        val cleanRms = rms.coerceAtLeast(8.0)

        if (rmsWindow.size >= 25) {
            rmsWindow.removeFirst()
        }
        rmsWindow.addLast(cleanRms.coerceAtMost(3200.0))

        val sortedWindow = rmsWindow.sorted()
        val percentileIdx = ((sortedWindow.size - 1) * 0.35).toInt().coerceIn(0, sortedWindow.lastIndex)
        val rollingPercentileFloor = sortedWindow[percentileIdx].coerceIn(24.0, 2400.0)

        val effectiveFloorBeforeUpdate = if (!hasDetectedSpeech) {
            maxOf(noiseFloorRms, rollingPercentileFloor * 0.90)
        } else {
            noiseFloorRms
        }

        val bgMode = listenBackgroundVoices
        val speechOnsetThreshold = if (bgMode) {
            maxOf(
                22.0,
                effectiveFloorBeforeUpdate * 1.16,
                effectiveFloorBeforeUpdate + 14.0
            )
        } else {
            maxOf(
                28.0,
                effectiveFloorBeforeUpdate * 1.25,
                effectiveFloorBeforeUpdate + 18.0
            )
        }

        val speechContinueThreshold = if (hasDetectedSpeech) {
            if (bgMode) {
                maxOf(
                    28.0,
                    effectiveFloorBeforeUpdate * 1.12 + 12.0,
                    peakSpeechRms * 0.15
                )
            } else {
                maxOf(
                    36.0,
                    effectiveFloorBeforeUpdate * 1.38 + 36.0,
                    peakSpeechRms * 0.30
                )
            }
        } else {
            speechOnsetThreshold
        }

        if (!hasDetectedSpeech) {
            noiseFloorRms = if (nonZeroFrameCount <= 3) {
                rollingPercentileFloor
            } else {
                (noiseFloorRms * 0.70 + rollingPercentileFloor * 0.30).coerceIn(24.0, 2400.0)
            }
        } else {
            if (cleanRms < speechContinueThreshold) {
                postSpeechDropFrames++
                if (cleanRms < peakSpeechRms * 0.30 && rollingPercentileFloor > noiseFloorRms && rollingPercentileFloor < peakSpeechRms * 0.30) {
                    noiseFloorRms = (noiseFloorRms * 0.82 + rollingPercentileFloor * 0.18).coerceIn(24.0, 2400.0)
                } else if (postSpeechDropFrames >= 2 && cleanRms > noiseFloorRms) {
                    noiseFloorRms = (noiseFloorRms * 0.65 + cleanRms * 0.35).coerceIn(24.0, 2400.0)
                } else if (cleanRms < noiseFloorRms) {
                    noiseFloorRms = (noiseFloorRms * 0.80 + cleanRms * 0.20).coerceIn(24.0, 2400.0)
                }
            } else {
                postSpeechDropFrames = 0
            }
        }

        val effectiveFloor = if (!hasDetectedSpeech) {
            maxOf(noiseFloorRms, rollingPercentileFloor * 0.90)
        } else {
            noiseFloorRms
        }

        val frameHasVoice = if (!hasDetectedSpeech) {
            val minFrames = if (bgMode) 1 else 2
            nonZeroFrameCount >= minFrames && cleanRms >= speechOnsetThreshold
        } else {
            cleanRms >= speechContinueThreshold
        }

        if (frameHasVoice) {
            consecutiveSpeechFrames++
            if (cleanRms > peakSpeechRms) {
                peakSpeechRms = cleanRms
            }
            if (steadySpeechWindow.size >= 22) {
                steadySpeechWindow.removeFirst()
            }
            steadySpeechWindow.addLast(cleanRms)
            val onsetFramesRequired = if (bgMode) 1 else 2
            if (!hasDetectedSpeech && consecutiveSpeechFrames >= onsetFramesRequired) {
                hasDetectedSpeech = true
                speechStartTimestampMs = elapsedMs
            }
            if (hasDetectedSpeech) {
                lastSpeechTimestampMs = elapsedMs
            }
        } else {
            consecutiveSpeechFrames = 0
            steadySpeechWindow.clear()
        }

        val silenceAfterSpeechMs = if (hasDetectedSpeech) {
            (elapsedMs - lastSpeechTimestampMs).coerceAtLeast(0L)
        } else {
            0L
        }

        val speechSpanMs = if (hasDetectedSpeech && speechStartTimestampMs >= 0L) {
            (elapsedMs - speechStartTimestampMs).coerceAtLeast(0L)
        } else {
            0L
        }

        // Guard against flat WebRTC/emulator room carrier hum locking `hasDetectedSpeech = true` after a quiet startup gap:
        if (hasDetectedSpeech &&
            silenceAfterSpeechMs == 0L &&
            speechSpanMs >= 2600L &&
            steadySpeechWindow.size >= 20 &&
            peakSpeechRms < 1900.0
        ) {
            val minSteady = steadySpeechWindow.minOrNull() ?: cleanRms
            val maxSteady = steadySpeechWindow.maxOrNull() ?: cleanRms
            if (minSteady > 15.0 && (maxSteady / minSteady) < 1.48) {
                noiseFloorRms = (maxSteady * 1.12).coerceIn(60.0, 2400.0)
                resetForNextUtterance()
                return FrameDecision(
                    hasSpoken = false,
                    isCurrentlySpeaking = false,
                    silenceAfterSpeechMs = 0L,
                    autoCutProgress = 0f,
                    shouldAutoStop = false,
                    timedOutWithoutSpeech = false,
                    normalizedMeterLevel = 1.0f
                )
            }
        }

        val progress = if (hasDetectedSpeech && silenceAfterSpeechMs > 0L) {
            (silenceAfterSpeechMs.toFloat() / pauseThresholdMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        val pausedAfterSpeech = hasDetectedSpeech && silenceAfterSpeechMs >= pauseThresholdMs
        val hitMaxSingleUtteranceSpeech = hasDetectedSpeech && speechSpanMs >= maxSpeechSpanMs
        val timedOutSilent = !hasDetectedSpeech && elapsedMs >= noSpeechTimeoutMs
        val hitMaxCap = elapsedMs >= maxUtteranceMs

        val snrRatio = (cleanRms / effectiveFloor.coerceAtLeast(24.0)).toFloat()
        val meter = if (hasDetectedSpeech && frameHasVoice) {
            (snrRatio * 3.0f).coerceIn(1.6f, 12f)
        } else {
            (snrRatio * 1.45f).coerceIn(0.4f, 4.4f)
        }

        return FrameDecision(
            hasSpoken = hasDetectedSpeech,
            isCurrentlySpeaking = frameHasVoice && hasDetectedSpeech,
            silenceAfterSpeechMs = silenceAfterSpeechMs,
            autoCutProgress = progress,
            shouldAutoStop = pausedAfterSpeech || hitMaxSingleUtteranceSpeech || timedOutSilent || hitMaxCap,
            timedOutWithoutSpeech = timedOutSilent && !hasDetectedSpeech,
            normalizedMeterLevel = meter
        )
    }
}

class VoiceAssistantEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SpeechRecognitionState())
    val state: StateFlow<SpeechRecognitionState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var systemSpeechSilenceRunnable: Runnable? = null
    private var systemSpeechStartupWatchdog: Runnable? = null
    private var lastPartialTextFromSystem: String = ""

    @Volatile
    private var systemSpeechBrokenOnDevice: Boolean = false

    @Volatile
    var listenBackgroundVoices: Boolean = false
        private set

    private var activeVadInstance: AdaptiveVoiceActivityDetector? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private val captureLock = Any()
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRecordingWav = AtomicBoolean(false)
    private val isPersistentAutoStream = AtomicBoolean(false)
    private val isStartingAutoCapture = AtomicBoolean(false)
    private val forceFlushCurrentUtterance = AtomicBoolean(false)
    private val resetCurrentAutoUtterance = AtomicBoolean(false)
    private val nudgePromptUntilEpochMs = AtomicLong(0L)
    private val pauseCaptureForProcessing = AtomicBoolean(false)
    private val pauseCaptureForTyping = AtomicBoolean(false)
    private val suppressCaptureUntilEpochMs = AtomicLong(0L)
    private val shouldSubmitWhenStopped = AtomicBoolean(true)

    private var onTextCallback: ((String, Float) -> Unit)? = null
    private var onAudioCallback: ((ByteArray, String) -> Unit)? = null
    private var onSilentCycleCallback: (() -> Unit)? = null

    enum class VoiceProfile {
        FEMALE,
        MALE,
        DEEP
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile
    private var activeTtsUtteranceStarted = false
    private var pendingSpeechText: String? = null
    private var pendingSpeechPreferBangla: Boolean = false
    private var pendingSpeechCallback: (() -> Unit)? = null
    private var speechRate: Float = 1.02f
    private var baseProfileRate: Float = 1.02f
    private var elderlySlowMode: Boolean = false
    private var currentPitch: Float = 1.24f
    private var currentVoiceProfile: VoiceProfile = VoiceProfile.FEMALE
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utteranceWatchdogs = ConcurrentHashMap<String, Runnable>()
    private val utteranceSeq = AtomicLong(0L)
    @Volatile
    private var activeFinalUtteranceId: String? = null

    private fun isRunningOnEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val hw = Build.HARDWARE.lowercase()
        val prod = Build.PRODUCT.lowercase()
        return "generic" in fp || "emulator" in fp || "sdk_gphone" in model || "emulator" in model ||
            "ranchu" in hw || "goldfish" in hw || "sdk_gphone" in prod || "vbox" in hw
    }

    init {
        val saved = try {
            com.example.core.preferences.PreferencesRepository.readCachedVoiceProfileSync(context.applicationContext)
        } catch (_: Exception) { "FEMALE" }
        val initProfile = when (saved.uppercase()) {
            "MALE" -> VoiceProfile.MALE
            "DEEP" -> VoiceProfile.DEEP
            else -> VoiceProfile.FEMALE
        }
        setVoiceProfile(initProfile)
        initTts()
    }

    private fun initTts() {
        mainHandler.post {
            val initWatchdog = Runnable {
                if (!ttsReady) {
                    val queuedCb = pendingSpeechCallback
                    pendingSpeechText = null
                    pendingSpeechCallback = null
                    _state.update { it.copy(isSpeaking = false) }
                    queuedCb?.invoke()
                }
            }
            mainHandler.postDelayed(initWatchdog, 3500L)
            try {
                tts = TextToSpeech(context.applicationContext) { status ->
                    mainHandler.removeCallbacks(initWatchdog)
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        tts?.language = Locale.US
                        tts?.setPitch(currentPitch)
                        tts?.setSpeechRate(speechRate)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                activeTtsUtteranceStarted = true
                                suppressCaptureUntilEpochMs.set(
                                    maxOf(suppressCaptureUntilEpochMs.get(), System.currentTimeMillis() + 12000L)
                                )
                                _state.update { it.copy(isSpeaking = true) }
                            }

                            override fun onDone(utteranceId: String?) {
                                finishUtterance(utteranceId)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                finishUtterance(utteranceId)
                            }
                        })
                        val queuedText = pendingSpeechText
                        val queuedBangla = pendingSpeechPreferBangla
                        val queuedCb = pendingSpeechCallback
                        pendingSpeechText = null
                        pendingSpeechCallback = null
                        if (!queuedText.isNullOrBlank()) {
                            speak(queuedText, preferBangla = queuedBangla, onComplete = queuedCb)
                        }
                    } else {
                        ttsReady = false
                        val queuedCb = pendingSpeechCallback
                        pendingSpeechText = null
                        pendingSpeechCallback = null
                        _state.update { it.copy(isSpeaking = false) }
                        queuedCb?.invoke()
                    }
                }
            } catch (e: Exception) {
                mainHandler.removeCallbacks(initWatchdog)
                Log.w(TAG, "TTS initialization unavailable", e)
                ttsReady = false
                val queuedCb = pendingSpeechCallback
                pendingSpeechText = null
                pendingSpeechCallback = null
                _state.update { it.copy(isSpeaking = false) }
                queuedCb?.invoke()
            }
        }
    }

    private fun finishUtterance(utteranceId: String?) {
        // Ignore intermediate sub-chunks from QUEUE_ADD chunking until the final chunk finishes
        if (utteranceId != null && "_part_" in utteranceId) {
            return
        }
        suppressCaptureUntilEpochMs.set(System.currentTimeMillis() + 350L)
        resetCurrentAutoUtterance.set(true)
        mainHandler.post {
            if (utteranceId != null) {
                utteranceWatchdogs.remove(utteranceId)?.let { mainHandler.removeCallbacks(it) }
            }
            if (utteranceId == null || utteranceId == activeFinalUtteranceId) {
                activeFinalUtteranceId = null
                _state.update { it.copy(isSpeaking = false) }
            }
            if (utteranceId != null) {
                utteranceCallbacks.remove(utteranceId)?.invoke()
            }
        }
    }

    fun isTtsInitialized(): Boolean = ttsReady

    fun isBanglaTtsAvailable(): Boolean {
        if (!ttsReady) return true
        return try {
            val bdAvail = tts?.isLanguageAvailable(Locale.forLanguageTag("bn-BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            val inAvail = tts?.isLanguageAvailable(Locale.forLanguageTag("bn-IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            bdAvail >= TextToSpeech.LANG_AVAILABLE || inAvail >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Exception) {
            true
        }
    }

    fun setPausedForProcessing(paused: Boolean) {
        pauseCaptureForProcessing.set(paused)
        if (!paused) {
            suppressCaptureUntilEpochMs.set(
                maxOf(suppressCaptureUntilEpochMs.get(), System.currentTimeMillis() + 120L)
            )
            if (isRecordingWav.get() && isPersistentAutoStream.get() && !pauseCaptureForTyping.get()) {
                mainHandler.post {
                    _state.update {
                        it.copy(
                            isListening = true,
                            hasDetectedVoiceActivity = false,
                            partialTranscript = "Auto • যেকোনো স্ক্রিনে কথা বলুন",
                            errorMessage = null
                        )
                    }
                }
            }
        }
    }

    fun setPausedForTyping(paused: Boolean) {
        val prev = pauseCaptureForTyping.getAndSet(paused)
        if (paused) {
            resetCurrentAutoUtterance.set(true)
            forceFlushCurrentUtterance.set(false)
            if (isRecordingWav.get() && isPersistentAutoStream.get()) {
                mainHandler.post {
                    _state.update {
                        it.copy(
                            hasDetectedVoiceActivity = false,
                            rmsDb = 0f,
                            recordingSeconds = 0,
                            autoCutProgress = 0f,
                            partialTranscript = "Auto • কথা বলুন"
                        )
                    }
                }
            }
        } else if (prev && isRecordingWav.get() && isPersistentAutoStream.get() && !pauseCaptureForProcessing.get()) {
            suppressCaptureUntilEpochMs.set(
                maxOf(suppressCaptureUntilEpochMs.get(), System.currentTimeMillis() + 180L)
            )
            mainHandler.post {
                _state.update {
                    it.copy(
                        isListening = true,
                        hasDetectedVoiceActivity = false,
                        partialTranscript = "Auto • যেকোনো স্ক্রিনে কথা বলুন",
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun setElderlySlowClarity(enabled: Boolean) {
        elderlySlowMode = enabled
        speechRate = if (enabled) 0.82f else baseProfileRate
        mainHandler.post {
            try {
                tts?.setSpeechRate(speechRate)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Dynamically toggles Background Voice / Sound Listening Mode via Voice Command ONLY (zero UI switches).
     * - When `enabled = false` (default): attaches hardware `NoiseSuppressor` + `AcousticEchoCanceler` and uses
     *   focused foreground-speaker VAD thresholds so ambient chatter/TV/fan noise is ignored while user speaks.
     * - When `enabled = true` (user commanded "তুমি ব্যাকগ্রাউন্ড শব্দ শোনো"): disables hardware noise suppression
     *   and switches VAD into high-sensitivity far-field mode so background voices/sounds are also heard.
     */
    fun setListenBackgroundVoicesMode(enabled: Boolean) {
        listenBackgroundVoices = enabled
        activeVadInstance?.listenBackgroundVoices = enabled
        try {
            noiseSuppressor?.enabled = !enabled
        } catch (_: Exception) {
        }
    }

    private fun configureHardwareAudioEffects(audioSessionId: Int) {
        releaseHardwareAudioEffects()
        if (audioSessionId <= 0) return
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = !listenBackgroundVoices
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseHardwareAudioEffects() {
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun getVoiceProfile(): VoiceProfile = currentVoiceProfile

    fun setVoiceProfile(profile: VoiceProfile) {
        currentVoiceProfile = profile
        when (profile) {
            VoiceProfile.FEMALE -> {
                currentPitch = 1.24f
                baseProfileRate = 1.02f
            }
            VoiceProfile.MALE -> {
                currentPitch = 0.72f
                baseProfileRate = 0.94f
            }
            VoiceProfile.DEEP -> {
                currentPitch = 0.55f
                baseProfileRate = 0.86f
            }
        }
        speechRate = if (elderlySlowMode) minOf(0.82f, baseProfileRate) else baseProfileRate
        mainHandler.post {
            try {
                tts?.setPitch(currentPitch)
                tts?.setSpeechRate(speechRate)
            } catch (_: Exception) {
            }
        }
    }

    fun speak(
        text: String,
        preferBangla: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("✓", "")
            .replace("•", " ")
            .trim()
            .take(1400)

        if (cleanText.isBlank()) {
            if (onComplete != null) {
                mainHandler.postDelayed({ onComplete.invoke() }, 200L)
            }
            return
        }

        if (!ttsReady) {
            mainHandler.post {
                if (ttsReady) {
                    speak(cleanText, preferBangla = preferBangla, onComplete = onComplete)
                } else {
                    pendingSpeechCallback?.invoke()
                    pendingSpeechText = cleanText
                    pendingSpeechPreferBangla = preferBangla
                    pendingSpeechCallback = onComplete
                }
            }
            return
        }

        val completedOnce = AtomicBoolean(false)
        val uttId = "hey_shuvo_utt_${System.currentTimeMillis()}_${utteranceSeq.incrementAndGet()}"
        val safeComplete: () -> Unit = {
            if (completedOnce.compareAndSet(false, true)) {
                utteranceWatchdogs.remove(uttId)?.let { mainHandler.removeCallbacks(it) }
                utteranceWatchdogs.remove("${uttId}_start")?.let { mainHandler.removeCallbacks(it) }
                suppressCaptureUntilEpochMs.set(System.currentTimeMillis() + 350L)
                resetCurrentAutoUtterance.set(true)
                if (activeFinalUtteranceId == uttId) {
                    activeFinalUtteranceId = null
                    _state.update { it.copy(isSpeaking = false) }
                }
                onComplete?.invoke()
            }
        }

        mainHandler.post {
            try {
                // Cancel any older pending utterance callbacks cleanly since QUEUE_FLUSH replaces them
                utteranceWatchdogs.values.forEach { mainHandler.removeCallbacks(it) }
                utteranceWatchdogs.clear()
                utteranceCallbacks.clear()

                activeFinalUtteranceId = uttId
                activeTtsUtteranceStarted = false
                utteranceCallbacks[uttId] = safeComplete

                val containsBanglaScript = cleanText.any { it in '\u0980'..'\u09FF' }
                val targetLocale = if (preferBangla || containsBanglaScript) {
                    val bnBd = Locale.forLanguageTag("bn-BD")
                    val bnIn = Locale.forLanguageTag("bn-IN")
                    val bdAvail = tts?.isLanguageAvailable(bnBd) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    val inAvail = tts?.isLanguageAvailable(bnIn) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    when {
                        bdAvail >= TextToSpeech.LANG_AVAILABLE -> bnBd
                        inAvail >= TextToSpeech.LANG_AVAILABLE -> bnIn
                        else -> Locale.US
                    }
                } else {
                    Locale.US
                }
                tts?.language = targetLocale
                try {
                    val voices = tts?.voices
                    if (!voices.isNullOrEmpty()) {
                        val langVoices = voices.filter {
                            it.locale.language == targetLocale.language && !it.isNetworkConnectionRequired
                        }.ifEmpty {
                            voices.filter { it.locale.language == targetLocale.language }
                        }.sortedBy { it.name.lowercase() }

                        val matched = when (currentVoiceProfile) {
                            VoiceProfile.FEMALE -> langVoices.firstOrNull {
                                val n = it.name.lowercase()
                                "female" in n || "woman" in n || "#female" in n || "-f-" in n || "_f_" in n ||
                                    "bnf" in n || "bdf" in n || "bng-f" in n || "sfg" in n ||
                                    "iob" in n || "iol" in n || "tpf" in n ||
                                    "tanisha" in n || "kalpana" in n || "heera" in n
                            } ?: langVoices.firstOrNull()
                            VoiceProfile.MALE, VoiceProfile.DEEP -> langVoices.firstOrNull {
                                val n = it.name.lowercase()
                                ("male" in n && "female" !in n) || ("man" in n && "woman" !in n) ||
                                    "#male" in n || "-m-" in n || "_m_" in n ||
                                    "bnm" in n || "bdm" in n || "bng-m" in n || "ban" in n ||
                                    "iom" in n || "tpm" in n ||
                                    "rishi" in n || "prabhat" in n || "hemant" in n || "bhashkar" in n || "pradeep" in n
                            } ?: if (langVoices.size >= 2) langVoices.last() else langVoices.firstOrNull()
                        }
                        if (matched != null) {
                            tts?.voice = matched
                        }
                    }
                } catch (_: Exception) {
                }
                tts?.setPitch(currentPitch)
                tts?.setSpeechRate(speechRate)

                val chunks = splitTtsSentenceChunks(cleanText, maxLen = 220)
                val watchdogMs = ((cleanText.length * 75L) / speechRate.coerceAtLeast(0.6f)).toLong().coerceIn(2400L, 18000L)
                suppressCaptureUntilEpochMs.set(System.currentTimeMillis() + minOf(watchdogMs, 6000L))
                _state.update { it.copy(isSpeaking = true) }

                var enqueueResult = TextToSpeech.ERROR
                chunks.forEachIndexed { idx, chunk ->
                    val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val chunkId = if (idx == chunks.lastIndex) uttId else "${uttId}_part_$idx"
                    val res = tts?.speak(chunk, queueMode, null, chunkId) ?: TextToSpeech.ERROR
                    if (idx == chunks.lastIndex) {
                        enqueueResult = res
                    }
                }

                if (enqueueResult != TextToSpeech.SUCCESS) {
                    utteranceCallbacks.remove(uttId)
                    mainHandler.postDelayed(safeComplete, 180L)
                } else {
                    val startGuard = Runnable {
                        if (!activeTtsUtteranceStarted && activeFinalUtteranceId == uttId) {
                            Log.w(TAG, "TTS utterance did not start within 2200ms; releasing mic cleanly")
                            utteranceCallbacks.remove(uttId)
                            safeComplete()
                        }
                    }
                    utteranceWatchdogs["${uttId}_start"] = startGuard
                    mainHandler.postDelayed(startGuard, 2200L)

                    val watchdog = Runnable {
                        utteranceWatchdogs.remove(uttId)
                        utteranceCallbacks.remove(uttId)
                        safeComplete()
                    }
                    utteranceWatchdogs[uttId] = watchdog
                    mainHandler.postDelayed(watchdog, watchdogMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak error", e)
                utteranceCallbacks.remove(uttId)
                mainHandler.postDelayed(safeComplete, 180L)
            }
        }
    }

    fun stopSpeaking() {
        suppressCaptureUntilEpochMs.set(System.currentTimeMillis() + 600L)
        resetCurrentAutoUtterance.set(true)
        mainHandler.post {
            activeFinalUtteranceId = null
            pendingSpeechText = null
            val pendingCb = pendingSpeechCallback
            pendingSpeechCallback = null
            utteranceWatchdogs.values.forEach { mainHandler.removeCallbacks(it) }
            utteranceWatchdogs.clear()
            try {
                tts?.stop()
            } catch (_: Exception) {
            }
            val callbacks = utteranceCallbacks.values.toList()
            utteranceCallbacks.clear()
            _state.update { it.copy(isSpeaking = false) }
            pendingCb?.invoke()
            callbacks.forEach { it.invoke() }
        }
    }

    fun isContinuousStreamActive(): Boolean = isRecordingWav.get() && isPersistentAutoStream.get()

    /**
     * Clears the in-progress utterance inside `AUTO • All Display` without tearing down the open `AudioRecord` stream.
     */
    fun discardCurrentAutoUtterance() {
        resetCurrentAutoUtterance.set(true)
        forceFlushCurrentUtterance.set(false)
        mainHandler.post {
            if (isContinuousStreamActive()) {
                _state.value = _state.value.copy(
                    isListening = true,
                    hasDetectedVoiceActivity = false,
                    recordingSeconds = 0,
                    autoCutProgress = 0f,
                    partialTranscript = "Auto • যেকোনো স্ক্রিনে কথা বলুন",
                    errorMessage = null
                )
            }
        }
    }

    /**
     * Recalibrates ambient floor and gives instant confirmation when the user taps the Mic/Orb while `AUTO` is standing by.
     */
    fun nudgeAutoStreamReady() {
        stopSpeaking()
        pauseCaptureForProcessing.set(false)
        resetCurrentAutoUtterance.set(true)
        forceFlushCurrentUtterance.set(false)
        nudgePromptUntilEpochMs.set(System.currentTimeMillis() + 3200L)
        mainHandler.post {
            _state.value = _state.value.copy(
                isListening = true,
                hasDetectedVoiceActivity = false,
                recordingSeconds = 0,
                autoCutProgress = 0f,
                partialTranscript = "বস, আমি শুনছি — এখন বলুন",
                errorMessage = null
            )
        }
    }

    fun startListening(
        languageTag: String = "bn-BD",
        preferDirectAiAudio: Boolean = true,
        continuousAutoMode: Boolean = false,
        onTextResult: (String, Float) -> Unit,
        onAudioResult: (ByteArray, String) -> Unit,
        onSilentCycle: (() -> Unit)? = null
    ) {
        onTextCallback = onTextResult
        onAudioCallback = onAudioResult
        onSilentCycleCallback = onSilentCycle

        // Coalesce duplicate or already-running All-Display Auto capture requests so AudioRecord is never torn down!
        if (continuousAutoMode && (
                (isRecordingWav.get() && isPersistentAutoStream.get() && audioRecord != null) ||
                    isStartingAutoCapture.get()
                )
        ) {
            pauseCaptureForProcessing.set(false)
            _state.value = _state.value.copy(
                isListening = true,
                partialTranscript = "Auto • যেকোনো স্ক্রিনে কথা বলুন",
                errorMessage = null
            )
            return
        }

        if (continuousAutoMode) {
            isStartingAutoCapture.set(true)
        } else {
            isStartingAutoCapture.set(false)
        }

        stopSpeaking()
        val canUseSystemSpeech = !preferDirectAiAudio &&
            !continuousAutoMode &&
            !systemSpeechBrokenOnDevice &&
            SpeechRecognizer.isRecognitionAvailable(context)
        if (canUseSystemSpeech) {
            mainHandler.post {
                try {
                    cleanupActiveCapture()
                    startSystemSpeechRecognizer(languageTag)
                } finally {
                    isStartingAutoCapture.set(false)
                }
            }
        } else {
            dispatchDirectWavStartAsync(continuousAutoMode, forceRawMicFallback = false)
        }
    }

    private fun dispatchDirectWavStartAsync(
        continuousAutoMode: Boolean,
        forceRawMicFallback: Boolean = false
    ) {
        Thread({
            synchronized(captureLock) {
                try {
                    if (continuousAutoMode && !forceRawMicFallback && isRecordingWav.get() && isPersistentAutoStream.get() && audioRecord != null) {
                        pauseCaptureForProcessing.set(false)
                        return@synchronized
                    }
                    cleanupActiveCapture()
                    startDirectWavAudioRecorder(continuousAutoMode, forceRawMicFallback)
                } finally {
                    isStartingAutoCapture.set(false)
                }
            }
        }, "HeyShuvoAudioStart").start()
    }

    private fun scheduleSystemSpeechAutoCut(delayMs: Long = 1350L) {
        systemSpeechSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            val captured = lastPartialTextFromSystem.trim()
            if (captured.isNotBlank()) {
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {
                }
                mainHandler.postDelayed({
                    if (_state.value.isListening && lastPartialTextFromSystem.isNotBlank()) {
                        val finalStr = lastPartialTextFromSystem.trim()
                        lastPartialTextFromSystem = ""
                        cleanupActiveCapture()
                        _state.value = _state.value.copy(
                            isListening = false,
                            partialTranscript = "",
                            finalTranscript = finalStr,
                            confidence = 0.94f,
                            rmsDb = 0f,
                            autoCutProgress = 0f,
                            errorMessage = null
                        )
                        onTextCallback?.invoke(finalStr, 0.94f)
                    }
                }, 550L)
            }
        }
        systemSpeechSilenceRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun startSystemSpeechRecognizer(languageTag: String) {
        try {
            lastPartialTextFromSystem = ""
            var readyCallbackFired = false
            val recognizerStartMs = System.currentTimeMillis()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        readyCallbackFired = true
                        systemSpeechStartupWatchdog?.let { mainHandler.removeCallbacks(it) }
                        _state.value = _state.value.copy(
                            isListening = true,
                            hasDetectedVoiceActivity = false,
                            activeBackend = VoiceCaptureBackend.SYSTEM_SPEECH,
                            recordingSeconds = 0,
                            autoCutProgress = 0f,
                            partialTranscript = "শুনছি... কথা বলুন",
                            errorMessage = null
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        readyCallbackFired = true
                        systemSpeechStartupWatchdog?.let { mainHandler.removeCallbacks(it) }
                        _state.value = _state.value.copy(hasDetectedVoiceActivity = true)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        _state.value = _state.value.copy(rmsDb = rmsdB.coerceIn(0.5f, 12f))
                        if (rmsdB > 4.2f && lastPartialTextFromSystem.isNotBlank()) {
                            scheduleSystemSpeechAutoCut(1350L)
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        systemSpeechSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
                    }

                    override fun onError(error: Int) {
                        systemSpeechStartupWatchdog?.let { mainHandler.removeCallbacks(it) }
                        systemSpeechSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
                        val elapsedMs = System.currentTimeMillis() - recognizerStartMs
                        Log.w(TAG, "SpeechRecognizer error code=$error after ${elapsedMs}ms")

                        val rescuedPartial = lastPartialTextFromSystem.trim()
                        if (rescuedPartial.isNotBlank()) {
                            lastPartialTextFromSystem = ""
                            cleanupActiveCapture()
                            _state.value = _state.value.copy(
                                isListening = false,
                                partialTranscript = "",
                                finalTranscript = rescuedPartial,
                                confidence = 0.92f,
                                rmsDb = 0f,
                                autoCutProgress = 0f,
                                errorMessage = null
                            )
                            onTextCallback?.invoke(rescuedPartial, 0.92f)
                            return
                        }

                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            cleanupActiveCapture()
                            _state.value = _state.value.copy(
                                isListening = false,
                                rmsDb = 0f,
                                autoCutProgress = 0f,
                                partialTranscript = "",
                                errorMessage = "মাইক্রোফোন পারমিশন প্রয়োজন (Allow Microphone)।"
                            )
                            return
                        }

                        val isEndOfTurnSilence = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && elapsedMs >= 2100L && readyCallbackFired

                        if (isEndOfTurnSilence) {
                            cleanupActiveCapture()
                            _state.value = _state.value.copy(
                                isListening = false,
                                rmsDb = 0f,
                                autoCutProgress = 0f,
                                partialTranscript = "",
                                errorMessage = null
                            )
                            return
                        }

                        // SpeechRecognizer missing/client failure -> mark broken and seamlessly switch to Direct WAV off UI thread!
                        if (!readyCallbackFired || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER) {
                            systemSpeechBrokenOnDevice = true
                        }
                        dispatchDirectWavStartAsync(continuousAutoMode = false, forceRawMicFallback = false)
                    }

                    override fun onResults(results: Bundle?) {
                        systemSpeechStartupWatchdog?.let { mainHandler.removeCallbacks(it) }
                        systemSpeechSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val topText = matches?.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                            ?: lastPartialTextFromSystem.trim()
                        lastPartialTextFromSystem = ""
                        val topScore = scores?.firstOrNull()?.takeIf { it > 0f } ?: 0.95f
                        cleanupActiveCapture()
                        _state.update {
                            it.copy(
                                isListening = false,
                                isContinuousAuto = false,
                                isMicZeroSignal = false,
                                partialTranscript = "",
                                finalTranscript = topText,
                                confidence = topScore,
                                rmsDb = 0f,
                                autoCutProgress = 0f,
                                errorMessage = null
                            )
                        }
                        if (topText.isNotBlank()) {
                            onTextCallback?.invoke(topText, topScore)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                            .trim()
                        if (partial.isNotBlank()) {
                            lastPartialTextFromSystem = partial
                            _state.update {
                                it.copy(
                                    hasDetectedVoiceActivity = true,
                                    partialTranscript = "🎙️ \"$partial\""
                                )
                            }
                            scheduleSystemSpeechAutoCut(1350L)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            // If SpeechRecognizer hangs without calling onReadyForSpeech within 1450ms (common on cloud emulators without Google Voice service),
            // seamlessly switch to Direct WAV Recorder off UI thread in the exact same tap!
            val startupGuard = Runnable {
                if (!readyCallbackFired && _state.value.isListening && _state.value.activeBackend == VoiceCaptureBackend.SYSTEM_SPEECH) {
                    Log.w(TAG, "SpeechRecognizer did not become ready within 1450ms; switching to Direct WAV")
                    systemSpeechBrokenOnDevice = true
                    dispatchDirectWavStartAsync(continuousAutoMode = false, forceRawMicFallback = false)
                }
            }
            systemSpeechStartupWatchdog = startupGuard
            mainHandler.postDelayed(startupGuard, 1450L)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1350L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1150L)
            }
            _state.update {
                it.copy(
                    isListening = true,
                    isContinuousAuto = false,
                    isMicZeroSignal = false,
                    activeBackend = VoiceCaptureBackend.SYSTEM_SPEECH,
                    partialTranscript = "শুনছি... কথা বলুন",
                    errorMessage = null
                )
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "System SpeechRecognizer failed to start, switching to Direct WAV Recorder", e)
            systemSpeechBrokenOnDevice = true
            dispatchDirectWavStartAsync(continuousAutoMode = false, forceRawMicFallback = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDirectWavAudioRecorder(
        continuousAutoMode: Boolean,
        forceRawMicFallback: Boolean = false
    ) {
        try {
            val candidateRates = intArrayOf(16000, 44100)
            val emulatorEnv = isRunningOnEmulator()
            val preferRawMicFirst = listenBackgroundVoices || forceRawMicFallback || emulatorEnv
            val candidateSources = if (preferRawMicFirst) {
                intArrayOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                )
            } else {
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )
            }

            var initializedRecord: AudioRecord? = null
            var chosenSampleRate = 16000

            outer@ for (rate in candidateRates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val bufSize = maxOf(minBuf * 2, rate / 5 * 2)
                for (source in candidateSources) {
                    try {
                        val candidate = AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            initializedRecord = candidate
                            chosenSampleRate = rate
                            break@outer
                        } else {
                            candidate.release()
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            if (initializedRecord == null) {
                _state.update {
                    it.copy(
                        isListening = false,
                        isContinuousAuto = false,
                        rmsDb = 0f,
                        autoCutProgress = 0f,
                        partialTranscript = "",
                        errorMessage = "মাইক্রোফোন চালু করা যায়নি। পারমিশন ও Mic চেক করুন।"
                    )
                }
                return
            }

            audioRecord = initializedRecord
            if (!forceRawMicFallback && !emulatorEnv) {
                configureHardwareAudioEffects(initializedRecord.audioSessionId)
            } else {
                releaseHardwareAudioEffects()
            }
            shouldSubmitWhenStopped.set(true)
            forceFlushCurrentUtterance.set(false)
            pauseCaptureForProcessing.set(false)
            isPersistentAutoStream.set(continuousAutoMode)
            isRecordingWav.set(true)

            initializedRecord.startRecording()
            if (initializedRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseHardwareAudioEffects()
                initializedRecord.release()
                audioRecord = null
                isRecordingWav.set(false)
                isPersistentAutoStream.set(false)
                _state.update {
                    it.copy(
                        isListening = false,
                        isContinuousAuto = false,
                        errorMessage = "মাইক্রোফোন অন্য অ্যাপ ব্যবহার করছে বা ব্লক করা আছে।"
                    )
                }
                return
            }

            _state.update {
                it.copy(
                    isListening = true,
                    isContinuousAuto = continuousAutoMode,
                    isMicZeroSignal = false,
                    hasDetectedVoiceActivity = false,
                    activeBackend = VoiceCaptureBackend.DIRECT_AI_AUDIO,
                    recordingSeconds = 0,
                    autoCutProgress = 0f,
                    partialTranscript = if (continuousAutoMode) "Auto ভয়েস সক্রিয় — কথা বলুন" else "শুনছি... কথা বলুন",
                    errorMessage = null
                )
            }

            val sampleRate = chosenSampleRate
            val shortChunkSize = (sampleRate / 12).coerceAtLeast(320) // ~83ms frames for snappy VAD response
            val pauseThresholdMs = if (continuousAutoMode) 720L else 760L
            val usingRawFallback = forceRawMicFallback || emulatorEnv

            if (continuousAutoMode) {
                startPersistentAutoLoop(initializedRecord, sampleRate, shortChunkSize, pauseThresholdMs, usingRawFallback)
            } else {
                startSingleManualUtteranceLoop(initializedRecord, sampleRate, shortChunkSize, pauseThresholdMs, usingRawFallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct WAV recording failed to start", e)
            cleanupActiveCapture()
            _state.update {
                it.copy(
                    isListening = false,
                    rmsDb = 0f,
                    autoCutProgress = 0f,
                    partialTranscript = "",
                    errorMessage = "মাইক্রোফোন চালু করা যায়নি: ${e.localizedMessage ?: "Permission চেক করুন"}"
                )
            }
        }
    }

    /**
     * Persistent single-AudioRecord stream for `AUTO • All Display` mode:
     * Keeps the hardware `AudioRecord` session continuously open inside the foreground service
     * so Android 14–16 never blocks background microphone capture when the user switches apps.
     * Uses two distinct sample counters (`vadClockSamples` monotonic vs `pcmBufferSamples` for WAV slicing)
     * so `elapsedMs` NEVER rewinds backwards when speech begins!
     */
    private fun startPersistentAutoLoop(
        record: AudioRecord,
        sampleRate: Int,
        shortChunkSize: Int,
        pauseThresholdMs: Long,
        usedRawFallback: Boolean = false
    ) {
        captureThread = Thread({
            val shortBuffer = ShortArray(shortChunkSize)
            val preRollChunks = ArrayDeque<ByteArray>(6)
            val utterancePcm = ByteArrayOutputStream(sampleRate * 2 * 9)
            val vad = AdaptiveVoiceActivityDetector(
                pauseThresholdMs = pauseThresholdMs,
                noSpeechTimeoutMs = 8500L,
                maxSpeechSpanMs = 12500L,
                maxUtteranceMs = 17500L,
                listenBackgroundVoices = listenBackgroundVoices
            )
            activeVadInstance = vad

            var vadClockSamples = 0L
            var pcmBufferSamples = 0L
            var firstSpeechSample = -1L
            var lastSpeechSample = -1L
            var maxUtterancePeak = 0
            var lastUiUpdateMs = 0L
            var consecutiveZeroPeakChunks = 0
            var switchingToRawMic = false

            fun resetUtteranceBuffers() {
                utterancePcm.reset()
                preRollChunks.clear()
                vad.resetForNextUtterance()
                vadClockSamples = 0L
                pcmBufferSamples = 0L
                firstSpeechSample = -1L
                lastSpeechSample = -1L
                maxUtterancePeak = 0
            }

            try {
                while (isRecordingWav.get() && isPersistentAutoStream.get()) {
                    val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readCount <= 0) {
                        Thread.sleep(20L)
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (resetCurrentAutoUtterance.getAndSet(false)) {
                        resetUtteranceBuffers()
                    }
                    val isSuppressed = pauseCaptureForProcessing.get() ||
                        pauseCaptureForTyping.get() ||
                        _state.value.isSpeaking ||
                        now < suppressCaptureUntilEpochMs.get()

                    if (isSuppressed) {
                        if (pcmBufferSamples > 0L || vad.hasDetectedSpeech || vadClockSamples > 0L) {
                            resetUtteranceBuffers()
                        }
                        if (now - lastUiUpdateMs >= 300L) {
                            lastUiUpdateMs = now
                            val isTypingNow = pauseCaptureForTyping.get()
                            val busyLabel = when {
                                _state.value.isSpeaking -> "Hey Shuvo কথা বলছে..."
                                isTypingNow -> "Auto • কথা বলুন"
                                else -> "অপেক্ষা করুন..."
                            }
                            mainHandler.post {
                                if (isRecordingWav.get() && isPersistentAutoStream.get()) {
                                    _state.value = _state.value.copy(
                                        isListening = isTypingNow,
                                        isContinuousAuto = true,
                                        hasDetectedVoiceActivity = false,
                                        rmsDb = 0f,
                                        recordingSeconds = 0,
                                        autoCutProgress = 0f,
                                        partialTranscript = busyLabel
                                    )
                                }
                            }
                        }
                        continue
                    }

                    var sumSq = 0.0
                    val byteChunk = ByteArray(readCount * 2)
                    var bi = 0
                    var chunkPeak = 0
                    for (i in 0 until readCount) {
                        val s = shortBuffer[i].toInt()
                        val absS = abs(s)
                        if (absS > chunkPeak) chunkPeak = absS
                        sumSq += s.toDouble() * s.toDouble()
                        byteChunk[bi++] = (s and 0xFF).toByte()
                        byteChunk[bi++] = ((s shr 8) and 0xFF).toByte()
                    }

                    if (chunkPeak == 0) {
                        consecutiveZeroPeakChunks++
                        if (!usedRawFallback && consecutiveZeroPeakChunks >= 12) {
                            Log.w(TAG, "Auto AudioRecord reading all zeros; switching to raw MIC without FX")
                            switchingToRawMic = true
                            break
                        }
                    } else {
                        consecutiveZeroPeakChunks = 0
                    }
                    val zeroSignalDetected = usedRawFallback && consecutiveZeroPeakChunks >= 24

                    val rms = sqrt(sumSq / readCount)
                    val wasSpeakingBefore = vad.hasDetectedSpeech

                    vadClockSamples += readCount
                    val acousticElapsedMs = (vadClockSamples * 1000L) / sampleRate
                    val decision = vad.processChunk(rms = rms, elapsedMs = acousticElapsedMs)

                    if (!decision.hasSpoken) {
                        if (wasSpeakingBefore) {
                            // False steady-carrier hum was auto-reset by VAD: clear PCM buffer cleanly
                            utterancePcm.reset()
                            pcmBufferSamples = 0L
                            firstSpeechSample = -1L
                            lastSpeechSample = -1L
                            maxUtterancePeak = 0
                        }
                        if (preRollChunks.size >= 4) {
                            preRollChunks.removeFirst()
                        }
                        preRollChunks.addLast(byteChunk)
                        if (decision.timedOutWithoutSpeech) {
                            vadClockSamples = 0L
                            vad.resetForNextUtterance()
                        }
                    } else {
                        if (!wasSpeakingBefore) {
                            utterancePcm.reset()
                            var preSamples = 0L
                            preRollChunks.forEach { chunk ->
                                utterancePcm.write(chunk)
                                preSamples += chunk.size / 2
                            }
                            preRollChunks.clear()
                            firstSpeechSample = preSamples
                            pcmBufferSamples = preSamples
                        }
                        if (chunkPeak > maxUtterancePeak) {
                            maxUtterancePeak = chunkPeak
                        }
                        utterancePcm.write(byteChunk)
                        pcmBufferSamples += readCount
                        if (decision.isCurrentlySpeaking) {
                            lastSpeechSample = pcmBufferSamples
                        }
                    }

                    val manualFlushRequested = forceFlushCurrentUtterance.getAndSet(false)

                    if (now - lastUiUpdateMs >= 100L) {
                        lastUiUpdateMs = now
                        val activeSpeechSec = if (decision.hasSpoken) {
                            ((pcmBufferSamples * 1000L) / sampleRate / 1000L).toInt()
                        } else {
                            0
                        }
                        val hint = when {
                            decision.hasSpoken -> "কথা শোনা হচ্ছে..."
                            zeroSignalDetected -> "মাইকে কোনো অডিও আসছে না — Preview/Chrome-এ Mic Allow দিন"
                            now < nudgePromptUntilEpochMs.get() -> "বস, আমি শুনছি — এখন বলুন"
                            else -> "Auto • যেকোনো স্ক্রিনে কথা বলুন"
                        }
                        mainHandler.post {
                            if (isRecordingWav.get() && isPersistentAutoStream.get() && !pauseCaptureForProcessing.get()) {
                                _state.value = _state.value.copy(
                                    isListening = true,
                                    isContinuousAuto = true,
                                    isMicZeroSignal = zeroSignalDetected,
                                    hasDetectedVoiceActivity = decision.hasSpoken,
                                    recordingSeconds = activeSpeechSec,
                                    rmsDb = if (zeroSignalDetected) 0f else decision.normalizedMeterLevel,
                                    autoCutProgress = decision.autoCutProgress,
                                    partialTranscript = hint,
                                    errorMessage = null
                                )
                            }
                        }
                    }

                    val readyToDispatch = (decision.shouldAutoStop && decision.hasSpoken) ||
                        (manualFlushRequested && utterancePcm.size() > sampleRate / 6)

                    if (readyToDispatch) {
                        val rawBytes = utterancePcm.toByteArray()
                        val speechStart = firstSpeechSample
                        val speechEnd = if (lastSpeechSample > speechStart) lastSpeechSample else pcmBufferSamples
                        val peak = maxUtterancePeak
                        val speechDurationMs = if (speechEnd > speechStart && speechStart >= 0L) {
                            ((speechEnd - speechStart) * 1000L) / sampleRate
                        } else {
                            ((rawBytes.size / 2) * 1000L) / sampleRate
                        }
                        resetUtteranceBuffers()

                        val minDurationMs = if (listenBackgroundVoices) 95L else 110L
                        val minPeak = if (listenBackgroundVoices) 18 else 24
                        val validHumanUtterance = manualFlushRequested ||
                            (rawBytes.size >= sampleRate / 5 && speechDurationMs >= minDurationMs && peak >= minPeak)

                        if (validHumanUtterance) {
                            pauseCaptureForProcessing.set(true)
                            val wavBytes = buildHighClarityCloudWav16kHz(
                                rawPcmBytes = rawBytes,
                                srcSampleRate = sampleRate,
                                firstSpeechSample = speechStart,
                                lastSpeechSample = speechEnd
                            )
                            mainHandler.post {
                                _state.value = _state.value.copy(
                                    isListening = false,
                                    hasDetectedVoiceActivity = false,
                                    isMicZeroSignal = false,
                                    rmsDb = 0f,
                                    recordingSeconds = 0,
                                    autoCutProgress = 0f,
                                    partialTranscript = "",
                                    confidence = 0.98f,
                                    errorMessage = null
                                )
                                onAudioCallback?.invoke(wavBytes, "audio/wav")
                            }
                        } else {
                            onSilentCycleCallback?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Persistent Auto AudioRecord loop error", e)
            } finally {
                if (activeVadInstance === vad) {
                    activeVadInstance = null
                }
                releaseHardwareAudioEffects()
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                try {
                    record.release()
                } catch (_: Exception) {
                }
                if (audioRecord === record) {
                    audioRecord = null
                }
                isPersistentAutoStream.set(false)
                isRecordingWav.set(false)
                if (switchingToRawMic) {
                    dispatchDirectWavStartAsync(continuousAutoMode = true, forceRawMicFallback = true)
                } else {
                    mainHandler.post {
                        _state.value = _state.value.copy(
                            isListening = false,
                            isContinuousAuto = false,
                            hasDetectedVoiceActivity = false,
                            rmsDb = 0f,
                            recordingSeconds = 0,
                            autoCutProgress = 0f,
                            partialTranscript = ""
                        )
                    }
                }
            }
        }, "HeyShuvoAutoStream").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Single-utterance Manual voice capture with automatic speech-pause cut and 16kHz clear WAV encoding.
     */
    private fun startSingleManualUtteranceLoop(
        initializedRecord: AudioRecord,
        sampleRate: Int,
        shortChunkSize: Int,
        pauseThresholdMs: Long,
        usedRawFallback: Boolean = false
    ) {
        captureThread = Thread({
            val pcmOut = ByteArrayOutputStream(sampleRate * 2 * 9)
            val shortBuffer = ShortArray(shortChunkSize)
            val vad = AdaptiveVoiceActivityDetector(
                pauseThresholdMs = pauseThresholdMs,
                noSpeechTimeoutMs = 6500L,
                maxSpeechSpanMs = 12500L,
                maxUtteranceMs = 17500L,
                listenBackgroundVoices = listenBackgroundVoices
            )
            activeVadInstance = vad
            val startTimeMs = System.currentTimeMillis()
            var totalSamplesRead = 0L
            var firstSpeechSampleIndex = -1L
            var lastSpeechSampleIndex = -1L
            var lastUiUpdateMs = 0L
            var maxPeakSample = 0
            var stoppedBecauseNoSpeech = false
            var consecutiveZeroPeakChunks = 0
            var switchingToRawMic = false

            try {
                while (isRecordingWav.get()) {
                    val readCount = initializedRecord.read(shortBuffer, 0, shortBuffer.size)
                    if (readCount > 0) {
                        var sumSq = 0.0
                        val byteChunk = ByteArray(readCount * 2)
                        var bi = 0
                        var chunkPeak = 0
                        for (i in 0 until readCount) {
                            val s = shortBuffer[i].toInt()
                            val absS = abs(s)
                            if (absS > chunkPeak) chunkPeak = absS
                            if (absS > maxPeakSample) maxPeakSample = absS
                            sumSq += s.toDouble() * s.toDouble()
                            byteChunk[bi++] = (s and 0xFF).toByte()
                            byteChunk[bi++] = ((s shr 8) and 0xFF).toByte()
                        }
                        if (chunkPeak == 0) {
                            consecutiveZeroPeakChunks++
                            if (!usedRawFallback && consecutiveZeroPeakChunks >= 10) {
                                Log.w(TAG, "Manual AudioRecord reading all zeros; switching to raw MIC without FX")
                                switchingToRawMic = true
                                break
                            }
                        } else {
                            consecutiveZeroPeakChunks = 0
                        }
                        val zeroSignalDetected = usedRawFallback && consecutiveZeroPeakChunks >= 20

                        pcmOut.write(byteChunk)
                        val chunkStartSample = totalSamplesRead
                        totalSamplesRead += readCount

                        val rms = sqrt(sumSq / readCount)
                        val acousticElapsedMs = (totalSamplesRead * 1000L) / sampleRate
                        val wallElapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(acousticElapsedMs)
                        val decision = vad.processChunk(rms = rms, elapsedMs = acousticElapsedMs)

                        if (decision.isCurrentlySpeaking) {
                            if (firstSpeechSampleIndex < 0L) {
                                firstSpeechSampleIndex = chunkStartSample
                            }
                            lastSpeechSampleIndex = totalSamplesRead
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdateMs >= 100L) {
                            lastUiUpdateMs = now
                            val elapsedSec = (wallElapsedMs / 1000L).toInt()
                            val statusHint = when {
                                decision.hasSpoken -> "কথা শোনা হচ্ছে..."
                                zeroSignalDetected -> "মাইকে কোনো শব্দ আসছে না — Chrome/Preview Mic Allow করুন"
                                elapsedSec >= 2 && maxPeakSample < 12 -> "মাইকে শব্দ আসছে না — একটু কাছে বলুন বা Mic চেক করুন"
                                else -> "শুনছি... কথা বলুন"
                            }
                            mainHandler.post {
                                if (isRecordingWav.get()) {
                                    _state.value = _state.value.copy(
                                        isContinuousAuto = false,
                                        isMicZeroSignal = zeroSignalDetected,
                                        rmsDb = if (zeroSignalDetected) 0f else decision.normalizedMeterLevel,
                                        hasDetectedVoiceActivity = decision.hasSpoken,
                                        recordingSeconds = elapsedSec,
                                        autoCutProgress = decision.autoCutProgress,
                                        partialTranscript = statusHint
                                    )
                                }
                            }
                        }

                        if (decision.shouldAutoStop) {
                            stoppedBecauseNoSpeech = decision.timedOutWithoutSpeech
                            isRecordingWav.set(false)
                            break
                        }
                    } else {
                        Thread.sleep(20L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord loop error", e)
            } finally {
                if (activeVadInstance === vad) {
                    activeVadInstance = null
                }
                releaseHardwareAudioEffects()
                try {
                    initializedRecord.stop()
                } catch (_: Exception) {
                }
                try {
                    initializedRecord.release()
                } catch (_: Exception) {
                }
                if (audioRecord === initializedRecord) {
                    audioRecord = null
                }
            }

            if (switchingToRawMic) {
                isRecordingWav.set(false)
                dispatchDirectWavStartAsync(continuousAutoMode = false, forceRawMicFallback = true)
                return@Thread
            }

            val submit = shouldSubmitWhenStopped.get()
            val rawPcmBytes = pcmOut.toByteArray()

            mainHandler.post {
                _state.value = _state.value.copy(
                    isListening = false,
                    isContinuousAuto = false,
                    isMicZeroSignal = maxPeakSample == 0,
                    hasDetectedVoiceActivity = false,
                    rmsDb = 0f,
                    recordingSeconds = 0,
                    autoCutProgress = 0f,
                    partialTranscript = ""
                )

                if (!submit) return@post

                if ((stoppedBecauseNoSpeech && maxPeakSample < 18) || maxPeakSample < 12) {
                    val err = if (maxPeakSample < 10) {
                        "মাইক্রোফোনে কোনো শব্দ আসেনি। ব্রাউজার (Chrome) বা ফোনের Microphone Allow দিন।"
                    } else {
                        null
                    }
                    _state.value = _state.value.copy(errorMessage = err)
                    onSilentCycleCallback?.invoke()
                    return@post
                }

                if (rawPcmBytes.size < sampleRate / 6) {
                    onSilentCycleCallback?.invoke()
                    return@post
                }

                val wavBytes = buildHighClarityCloudWav16kHz(
                    rawPcmBytes = rawPcmBytes,
                    srcSampleRate = sampleRate,
                    firstSpeechSample = firstSpeechSampleIndex,
                    lastSpeechSample = lastSpeechSampleIndex
                )
                _state.value = _state.value.copy(confidence = 0.98f, errorMessage = null, isMicZeroSignal = false)
                onAudioCallback?.invoke(wavBytes, "audio/wav")
            }
        }, "HeyShuvoWavRecorder").apply {
            isDaemon = true
            start()
        }
    }

    fun stopListening(submitRecordedAudio: Boolean = true) {
        if (submitRecordedAudio && isPersistentAutoStream.get() && isRecordingWav.get()) {
            forceFlushCurrentUtterance.set(true)
            return
        }
        shouldSubmitWhenStopped.set(submitRecordedAudio)
        isPersistentAutoStream.set(false)
        isRecordingWav.set(false)
        mainHandler.post {
            systemSpeechStartupWatchdog?.let { mainHandler.removeCallbacks(it) }
            systemSpeechSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
            if (_state.value.activeBackend == VoiceCaptureBackend.SYSTEM_SPEECH && speechRecognizer != null) {
                val capturedPartial = lastPartialTextFromSystem.trim()
                try {
                    if (submitRecordedAudio) {
                        speechRecognizer?.stopListening()
                    } else {
                        lastPartialTextFromSystem = ""
                        speechRecognizer?.cancel()
                    }
                } catch (_: Exception) {
                }
                if (submitRecordedAudio && capturedPartial.isNotBlank()) {
                    mainHandler.postDelayed({
                        if (lastPartialTextFromSystem.isNotBlank()) {
                            val textOut = lastPartialTextFromSystem.trim()
                            lastPartialTextFromSystem = ""
                            cleanupActiveCapture()
                            _state.value = _state.value.copy(
                                isListening = false,
                                isContinuousAuto = false,
                                rmsDb = 0f,
                                autoCutProgress = 0f,
                                partialTranscript = "",
                                finalTranscript = textOut
                            )
                            onTextCallback?.invoke(textOut, 0.94f)
                        }
                    }, 450L)
                } else {
                    if (submitRecordedAudio) {
                        mainHandler.postDelayed({
                            if (_state.value.isListening && _state.value.activeBackend == VoiceCaptureBackend.SYSTEM_SPEECH) {
                                cleanupActiveCapture()
                                _state.value = _state.value.copy(
                                    isListening = false,
                                    isContinuousAuto = false,
                                    rmsDb = 0f,
                                    autoCutProgress = 0f,
                                    partialTranscript = ""
                                )
                            }
                        }, 450L)
                    } else {
                        cleanupActiveCapture()
                        _state.value = _state.value.copy(
                            isListening = false,
                            isContinuousAuto = false,
                            rmsDb = 0f,
                            autoCutProgress = 0f,
                            partialTranscript = ""
                        )
                    }
                }
            }
        }
    }

    private fun cleanupActiveCapture() {
        synchronized(captureLock) {
            systemSpeechStartupWatchdog?.let { mainHandler.removeCallbacks(it) }
            systemSpeechStartupWatchdog = null
            systemSpeechSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
            systemSpeechSilenceRunnable = null
            shouldSubmitWhenStopped.set(false)
            isPersistentAutoStream.set(false)
            isRecordingWav.set(false)
            pauseCaptureForProcessing.set(false)
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null

            val activeRec = audioRecord
            audioRecord = null
            if (activeRec != null) {
                try {
                    activeRec.stop()
                } catch (_: Exception) {
                }
                try {
                    activeRec.release()
                } catch (_: Exception) {
                }
            }
            val oldThread = captureThread
            captureThread = null
            if (oldThread != null && oldThread !== Thread.currentThread() && oldThread.isAlive) {
                oldThread.interrupt()
                runCatching { oldThread.join(180L) }
            }
        }
    }

    fun shutdown() {
        mainHandler.post {
            cleanupActiveCapture()
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {
            }
            utteranceCallbacks.clear()
            tts = null
        }
    }

    companion object {
        const val CLOUD_TARGET_SAMPLE_RATE = 8000
        const val HIGH_CLARITY_SAMPLE_RATE = 16000

        /**
         * Splits long Bangla/English replies into sentence-safe chunks (<= maxLen)
         * so OEM Android TTS engines never silently truncate or timeout mid-speech.
         */
        fun splitTtsSentenceChunks(rawText: String, maxLen: Int = 220): List<String> {
            val clean = rawText.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) return emptyList()
            if (clean.length <= maxLen) return listOf(clean)

            val sentences = clean.split(Regex("(?<=[।.!?])\\s+|\\n+"))
            val chunks = mutableListOf<String>()
            var buf = ""
            for (rawS in sentences) {
                val part = rawS.trim()
                if (part.isEmpty()) continue
                val combined = if (buf.isEmpty()) part else "$buf $part"
                if (combined.length <= maxLen) {
                    buf = combined
                } else {
                    if (buf.isNotEmpty()) {
                        chunks.add(buf)
                        buf = ""
                    }
                    if (part.length <= maxLen) {
                        buf = part
                    } else {
                        for (w in part.split(Regex("\\s+"))) {
                            if (w.isEmpty()) continue
                            val next = if (buf.isEmpty()) w else "$buf $w"
                            if (next.length <= maxLen) {
                                buf = next
                            } else {
                                if (buf.isNotEmpty()) chunks.add(buf)
                                buf = w.take(maxLen)
                            }
                        }
                    }
                }
            }
            if (buf.isNotEmpty()) chunks.add(buf)
            return chunks
        }

        /**
         * High-clarity 16,000 Hz mono PCM16 WAV builder so Bangla/Banglish sibilants ("শুভ", "Shuvo", "সেটিংস")
         * are preserved with crisp consonant detail while trimming leading/trailing silence.
         */
        fun buildHighClarityCloudWav16kHz(
            rawPcmBytes: ByteArray,
            srcSampleRate: Int = 16000,
            firstSpeechSample: Long = -1L,
            lastSpeechSample: Long = -1L
        ): ByteArray {
            val normalized = trimAndNormalizePcm16(
                rawPcmBytes = rawPcmBytes,
                sampleRate = srcSampleRate,
                firstSpeechSample = firstSpeechSample,
                lastSpeechSample = lastSpeechSample
            )
            val pcm16k = if (srcSampleRate > HIGH_CLARITY_SAMPLE_RATE) {
                downsamplePcm16(normalized, srcRate = srcSampleRate, targetRate = HIGH_CLARITY_SAMPLE_RATE)
            } else {
                normalized
            }
            val outRate = if (srcSampleRate > HIGH_CLARITY_SAMPLE_RATE) HIGH_CLARITY_SAMPLE_RATE else srcSampleRate
            val maxBytes = outRate * 2 * 12 // Cap at 12 seconds (~384 KB max, typically 30–90 KB) so longer sentences never clip
            val cappedPcm = if (pcm16k.size > maxBytes) {
                pcm16k.copyOfRange(0, maxBytes - (maxBytes % 2))
            } else {
                pcm16k
            }
            return encodePcm16MonoToWav(cappedPcm, sampleRate = outRate)
        }

        /**
         * Trims silence, removes DC bias, normalizes speech gain, and downsamples to 8,000 Hz mono PCM16.
         */
        fun buildCompactCloudWav8kHz(
            rawPcmBytes: ByteArray,
            srcSampleRate: Int = 16000,
            firstSpeechSample: Long = -1L,
            lastSpeechSample: Long = -1L
        ): ByteArray {
            val normalized = trimAndNormalizePcm16(
                rawPcmBytes = rawPcmBytes,
                sampleRate = srcSampleRate,
                firstSpeechSample = firstSpeechSample,
                lastSpeechSample = lastSpeechSample
            )
            val compactPcm = downsamplePcm16(
                pcmBytes = normalized,
                srcRate = srcSampleRate,
                targetRate = CLOUD_TARGET_SAMPLE_RATE
            )
            val maxBytes = CLOUD_TARGET_SAMPLE_RATE * 2 * 11 / 2
            val cappedPcm = if (compactPcm.size > maxBytes) {
                compactPcm.copyOfRange(0, maxBytes - (maxBytes % 2))
            } else {
                compactPcm
            }
            return encodePcm16MonoToWav(cappedPcm, sampleRate = CLOUD_TARGET_SAMPLE_RATE)
        }

        fun downsamplePcm16(
            pcmBytes: ByteArray,
            srcRate: Int,
            targetRate: Int = CLOUD_TARGET_SAMPLE_RATE
        ): ByteArray {
            if (srcRate <= targetRate || pcmBytes.size < 4) return pcmBytes
            val srcSamples = pcmBytes.size / 2
            val outSamples = ((srcSamples.toLong() * targetRate) / srcRate).toInt().coerceAtLeast(1)
            val out = ByteArray(outSamples * 2)
            val ratio = srcRate.toDouble() / targetRate.toDouble()

            for (i in 0 until outSamples) {
                val startIdx = (i * ratio).toInt().coerceIn(0, srcSamples - 1)
                val endIdx = (((i + 1) * ratio).toInt()).coerceIn(startIdx + 1, srcSamples)
                var sum = 0L
                var count = 0
                for (sIdx in startIdx until endIdx) {
                    val b0 = pcmBytes[sIdx * 2].toInt() and 0xFF
                    val b1 = pcmBytes[sIdx * 2 + 1].toInt()
                    val sample = (b1 shl 8) or b0
                    sum += sample
                    count++
                }
                val avg = (if (count > 0) sum / count else 0L).toInt().coerceIn(-32767, 32767)
                out[i * 2] = (avg and 0xFF).toByte()
                out[i * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
            }
            return out
        }

        fun trimAndNormalizePcm16(
            rawPcmBytes: ByteArray,
            sampleRate: Int = 16000,
            firstSpeechSample: Long = -1L,
            lastSpeechSample: Long = -1L
        ): ByteArray {
            val totalSamples = rawPcmBytes.size / 2
            if (totalSamples <= 0) return rawPcmBytes

            val preRollSamples = (sampleRate * 180L) / 1000L
            val postRollSamples = (sampleRate * 200L) / 1000L

            val startSample = if (firstSpeechSample > 0L) {
                (firstSpeechSample - preRollSamples).coerceIn(0L, totalSamples.toLong()).toInt()
            } else {
                0
            }
            val endSample = if (lastSpeechSample > startSample) {
                (lastSpeechSample + postRollSamples).coerceIn(startSample.toLong() + 1L, totalSamples.toLong()).toInt()
            } else {
                totalSamples
            }

            val sliceCount = (endSample - startSample).coerceAtLeast(1)

            var sumSample = 0L
            for (idx in startSample until endSample) {
                val b0 = rawPcmBytes[idx * 2].toInt() and 0xFF
                val b1 = rawPcmBytes[idx * 2 + 1].toInt()
                val sample = (b1 shl 8) or b0
                sumSample += sample
            }
            val dcOffset = (sumSample / sliceCount).toInt()

            val bins = IntArray(128)
            var maxAbs = 1
            for (idx in startSample until endSample) {
                val b0 = rawPcmBytes[idx * 2].toInt() and 0xFF
                val b1 = rawPcmBytes[idx * 2 + 1].toInt()
                val centered = ((b1 shl 8) or b0) - dcOffset
                val a = abs(centered)
                if (a > maxAbs) maxAbs = a
                val bin = (a / 256).coerceIn(0, 127)
                bins[bin]++
            }
            val targetRank = (sliceCount * 0.985).toInt().coerceAtLeast(1)
            var cumulative = 0
            var robustPeak = maxAbs
            for (b in 0..127) {
                cumulative += bins[b]
                if (cumulative >= targetRank) {
                    robustPeak = maxOf(1, (b + 1) * 256).coerceAtMost(maxAbs)
                    break
                }
            }

            val gain = (24000.0 / robustPeak.toDouble()).coerceIn(1.0, 48.0)
            val out = ByteArray(sliceCount * 2)
            var outIdx = 0
            for (idx in startSample until endSample) {
                val b0 = rawPcmBytes[idx * 2].toInt() and 0xFF
                val b1 = rawPcmBytes[idx * 2 + 1].toInt()
                val centered = ((b1 shl 8) or b0) - dcOffset
                val boosted = (centered * gain).toInt().coerceIn(-32767, 32767)
                out[outIdx++] = (boosted and 0xFF).toByte()
                out[outIdx++] = ((boosted shr 8) and 0xFF).toByte()
            }
            return out
        }

        fun encodePcm16MonoToWav(pcmBytes: ByteArray, sampleRate: Int = 16000): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)
            val totalDataLen = 36 + pcmBytes.size

            val buffer = ByteBuffer.allocate(44 + pcmBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put('R'.code.toByte())
            buffer.put('I'.code.toByte())
            buffer.put('F'.code.toByte())
            buffer.put('F'.code.toByte())
            buffer.putInt(totalDataLen)
            buffer.put('W'.code.toByte())
            buffer.put('A'.code.toByte())
            buffer.put('V'.code.toByte())
            buffer.put('E'.code.toByte())
            buffer.put('f'.code.toByte())
            buffer.put('m'.code.toByte())
            buffer.put('t'.code.toByte())
            buffer.put(' '.code.toByte())
            buffer.putInt(16)
            buffer.putShort(1.toShort())
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())
            buffer.put('d'.code.toByte())
            buffer.put('a'.code.toByte())
            buffer.put('t'.code.toByte())
            buffer.put('a'.code.toByte())
            buffer.putInt(pcmBytes.size)
            buffer.put(pcmBytes)
            return buffer.array()
        }
    }
}
