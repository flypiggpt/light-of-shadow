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
    }

    private data class PendingSpeech(val key: String, val text: String)

    private val appContext = context.applicationContext
    private val lastSpokenAt = ConcurrentHashMap<String, Long>()
    private val lastSpokenText = ConcurrentHashMap<String, String>()
    @Volatile private var isReady = false
    @Volatile private var initializationError: String? = null
    @Volatile private var lastSpeakError: String? = null
    @Volatile private var pendingSpeech: PendingSpeech? = null
    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            // 对 Google TTS 等第三方引擎更宽容：只要不是明确的错误码，就尝试配置
            val shouldConfigure = status == TextToSpeech.SUCCESS || 
                    (status >= 0 && status <= 1)  // 某些引擎可能返回 0 或 1 作为成功/警告
            
            if (shouldConfigure) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val configureError = configureEngine()
                        if (configureError == null) {
                            isReady = true
                            initializationError = null
                            Log.d(TAG, "TTS engine initialized successfully (status code: $status)")
                            pendingSpeech?.let { pending ->
                                pendingSpeech = null
                                speakInternal(pending.key, pending.text)
                            }
                        } else {
                            isReady = false
                            initializationError = configureError
                            Log.e(TAG, configureError)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error configuring TTS engine", e)
                        isReady = false
                        initializationError = appContext.getString(R.string.tts_init_failed_hint)
                    }
                }
            } else {
                isReady = false
                val errorMsg = describeInitializationError(status)
                initializationError = errorMsg
                Log.e(TAG, "TTS initialization failed: $errorMsg (status code: $status)")
            }
        }
    }

    @Volatile
    private var speechRate: Float = AppSettings.speechRate

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 1.4f)
        runCatching { textToSpeech.setSpeechRate(speechRate) }
    }

    fun isReady(): Boolean = isReady

    fun getInitializationError(): String? = initializationError

    fun getLastSpeakError(): String? = lastSpeakError

    private fun configureEngine(): String? {
        val engine = textToSpeech
        
        // 尝试设置中文，宽容处理验证失败的情况（某些 TTS 引擎如 Google TTS 可能需要时间加载）
        val chineseLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA)
        var selectedLocale: Locale? = null
        var chineseWarning: String? = null
        
        for (locale in chineseLocales) {
            val availability = engine.isLanguageAvailable(locale)
            Log.d(TAG, "Language availability for $locale: $availability")
            
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                selectedLocale = locale
                break
            } else if (availability == TextToSpeech.LANG_MISSING_DATA) {
                // 语音包缺失但引擎支持此语言，先記錄，稍后嘗試設置
                if (selectedLocale == null) {
                    selectedLocale = locale
                    chineseWarning = "中文语言需要下载语音数据，但 TTS 引擎支持此语言，继续初始化"
                }
            }
        }
        
        // 如果找不到任何中文配置，降级到英文
        if (selectedLocale == null) {
            Log.w(TAG, "Chinese not available, fall back to English")
            selectedLocale = Locale.US
        }
        
        // 尝试设置语言，但不因失败而阻止初始化
        try {
            val languageResult = engine.setLanguage(selectedLocale)
            Log.d(TAG, "setLanguage($selectedLocale) returned: $languageResult")
            
            // 即使语言设置返回警告状态（如 LANG_MISSING_DATA），也继续初始化
            if (languageResult < TextToSpeech.LANG_AVAILABLE && languageResult != TextToSpeech.LANG_MISSING_DATA) {
                Log.w(TAG, describeLanguageError(languageResult))
            }
            
            if (chineseWarning != null) {
                Log.w(TAG, chineseWarning)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting language $selectedLocale, continuing anyway", e)
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
        Log.d(TAG, "TTS engine configured successfully with language: $selectedLocale")
        return null
    }

    private fun describeInitializationError(status: Int): String {
        return when (status) {
            TextToSpeech.ERROR -> appContext.getString(R.string.tts_init_failed_hint)
            TextToSpeech.ERROR_SYNTHESIS -> appContext.getString(R.string.tts_speak_failed_hint)
            TextToSpeech.ERROR_SERVICE -> "TTS 服务不可用，请检查系统语音引擎是否已启用"
            TextToSpeech.ERROR_INVALID_REQUEST -> "TTS 初始化请求无效，请重启应用后重试"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> appContext.getString(R.string.tts_missing_voice_data_hint)
            TextToSpeech.ERROR_NETWORK -> "TTS 需要网络但当前网络不可用"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "TTS 网络请求超时，请检查网络后重试"
            else -> "TTS 初始化失败（状态码：$status）"
        }
    }

    private fun describeLanguageError(result: Int): String {
        return when (result) {
            TextToSpeech.LANG_MISSING_DATA -> appContext.getString(R.string.tts_missing_voice_data_hint)
            TextToSpeech.LANG_NOT_SUPPORTED -> appContext.getString(R.string.tts_unsupported_language_hint)
            else -> "TTS 语言设置失败（状态码：$result）"
        }
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
            val now = System.currentTimeMillis()
            lastSpokenAt[key] = now
            lastSpokenAt["__global__"] = now
            lastSpokenText[key] = text
            val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
            if (result == TextToSpeech.SUCCESS) {
                lastSpeakError = null
                Log.d(TAG, "Speaking: $text (key: $key)")
            } else {
                lastSpeakError = describeSpeakError(result)
                Log.w(TAG, "Failed to speak text: $text (result code: $result, message: $lastSpeakError)")
            }
            result == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            lastSpeakError = appContext.getString(R.string.tts_speak_failed_hint)
            Log.e(TAG, "Exception while speaking: $text", e)
            false
        }
    }

    private fun describeSpeakError(result: Int): String {
        return when (result) {
            TextToSpeech.ERROR -> appContext.getString(R.string.tts_speak_failed_hint)
            TextToSpeech.ERROR_SERVICE -> "TTS 播报失败：语音服务不可用"
            TextToSpeech.ERROR_INVALID_REQUEST -> "TTS 播报失败：请求无效"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> appContext.getString(R.string.tts_missing_voice_data_hint)
            TextToSpeech.ERROR_NETWORK -> "TTS 播报失败：网络不可用"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "TTS 播报失败：网络超时"
            else -> "TTS 播报失败（状态码：$result）"
        }
    }

    override fun close() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }
}
