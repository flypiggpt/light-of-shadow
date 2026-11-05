package com.yan.client.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.yan.client.R
import com.yan.client.settings.AppSettings
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TtsAlertManager(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "TtsAlertManager"
        private val ENGINE_CANDIDATES = listOf(
            "com.google.android.tts",
            "com.xiaomi.mibrain.speech",
            "com.svox.pico"
        )
    }

    private data class PendingSpeech(val key: String, val text: String)

    private val appContext = context.applicationContext
    private val lastSpokenAt = ConcurrentHashMap<String, Long>()
    private val lastSpokenText = ConcurrentHashMap<String, String>()
    @Volatile private var isReady = false
    @Volatile private var initializationError: String? = null
    @Volatile private var lastSpeakError: String? = null
    @Volatile private var pendingSpeech: PendingSpeech? = null
    @Volatile private var speechRate: Float = 1.0f
    private var engineIndex = 0
    private var textToSpeech: TextToSpeech? = null

    init {
        speechRate = runCatching { AppSettings.speechRate }.getOrDefault(1.0f)
        initEngine(ENGINE_CANDIDATES[0])
    }

    private fun initEngine(engineName: String) {
        Log.i(TAG, "Creating TTS engine=$engineName")
        try {
            val listener = TextToSpeech.OnInitListener { status -> onEngineStatus(status, engineName) }
            textToSpeech = TextToSpeech(appContext, listener, engineName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TTS engine=$engineName", e)
            tryNextEngine()
        }
    }

    private fun onEngineStatus(status: Int, engineName: String) {
        Log.i(TAG, "TTS init callback engine=$engineName status=$status")
        if (status == TextToSpeech.SUCCESS || status == 0 || status == 1) {
            Handler(Looper.getMainLooper()).post {
                val configureError = configureEngine()
                if (configureError == null) {
                    isReady = true
                    initializationError = null
                    Log.i(TAG, "TTS ready via $engineName rate=$speechRate")
                    pendingSpeech?.let { pending ->
                        pendingSpeech = null
                        speakInternal(pending.key, pending.text)
                    }
                } else {
                    Log.e(TAG, "configureEngine failed: $configureError")
                    tryNextEngine()
                }
            }
        } else {
            tryNextEngine()
        }
    }

    private fun tryNextEngine() {
        engineIndex++
        if (engineIndex >= ENGINE_CANDIDATES.size) {
            isReady = false
            initializationError = initializationError ?: appContext.getString(R.string.tts_init_failed_hint)
            Log.e(TAG, "All TTS engines failed. lastError=$initializationError")
        } else {
            val next = ENGINE_CANDIDATES[engineIndex]
            Log.w(TAG, "Trying next TTS engine: $next")
            runCatching { textToSpeech?.shutdown() }
            initEngine(next)
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 1.4f)
        runCatching { textToSpeech?.setSpeechRate(speechRate) }
    }

    fun isReady(): Boolean = isReady

    fun getInitializationError(): String? = initializationError

    fun getLastSpeakError(): String? = lastSpeakError

    private fun configureEngine(): String? {
        val engine = textToSpeech ?: return "TTS 引擎未创建"
        val chineseLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA)
        var selectedLocale: Locale? = null
        for (locale in chineseLocales) {
            val availability = engine.isLanguageAvailable(locale)
            Log.d(TAG, "Language availability for $locale: $availability")
            if (availability >= TextToSpeech.LANG_AVAILABLE || availability == TextToSpeech.LANG_MISSING_DATA) {
                selectedLocale = locale
                break
            }
        }
        if (selectedLocale == null) {
            Log.w(TAG, "Chinese not available, fall back to English")
            selectedLocale = Locale.US
        }
        try {
            val languageResult = engine.setLanguage(selectedLocale)
            Log.d(TAG, "setLanguage($selectedLocale) returned: $languageResult")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting language $selectedLocale", e)
        }
        engine.setSpeechRate(speechRate)
        engine.setPitch(1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        return null
    }

    fun speakIfAllowed(
        key: String,
        text: String,
        cooldownMs: Long = 3000L,
        globalCooldownMs: Long = 1000L
    ) {
        if (text.isBlank()) return
        if (!isReady) {
            pendingSpeech = PendingSpeech(key, text)
            return
        }
        val now = System.currentTimeMillis()
        val lastKeyTime = lastSpokenAt[key] ?: 0L
        val lastText = lastSpokenText[key]
        if (now - lastKeyTime < cooldownMs && lastText == text) return
        val globalKey = "__global__"
        val lastGlobalTime = lastSpokenAt[globalKey] ?: 0L
        if (now - lastGlobalTime < globalCooldownMs) return
        speakInternal(key, text)
    }

    fun speakNow(text: String, key: String = text): Boolean {
        if (text.isBlank()) return false
        if (!isReady) {
            pendingSpeech = PendingSpeech(key, text)
            return false
        }
        return speakInternal(key, text)
    }

    private fun speakInternal(key: String, text: String): Boolean {
        return try {
            val engine = textToSpeech ?: return false
            val now = System.currentTimeMillis()
            lastSpokenAt[key] = now
            lastSpokenAt["__global__"] = now
            lastSpokenText[key] = text
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
            if (result == TextToSpeech.SUCCESS) {
                lastSpeakError = null
                Log.i(TAG, "Speaking: $text")
                true
            } else {
                lastSpeakError = "TTS 播报失败（状态码：$result）"
                Log.w(TAG, "Failed to speak: $text (code=$result)")
                false
            }
        } catch (e: Exception) {
            lastSpeakError = "TTS 播报异常：${e.message}"
            Log.e(TAG, "Exception while speaking: $text", e)
            false
        }
    }

    override fun close() {
        runCatching {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        textToSpeech = null
    }
}
