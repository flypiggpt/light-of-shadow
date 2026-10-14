package com.yan.client.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 离线 TTS 管理器，尝试多个系统 TTS 引擎
 * 优先顺序：Pico TTS -> 系统语音引擎 -> Google TTS -> 其他
 */
class OfflineTtsManager(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "OfflineTtsManager"
        // Pico TTS 引擎包名
        private const val PICO_TTS_PACKAGE = "com.svox.pico"
    }

    private val lastSpokenAt = ConcurrentHashMap<String, Long>()
    private val lastSpokenText = ConcurrentHashMap<String, String>()
    @Volatile private var isReady = false
    @Volatile private var isSilentMode = false
    @Volatile private var initializationError: String? = null
    @Volatile private var lastSpeakError: String? = null
    @Volatile private var currentEngine: String = "Unknown"
    private lateinit var textToSpeech: TextToSpeech

    init {
        // 在后台线程初始化，避免阻塞主线程
        thread(isDaemon = true, start = true) {
            initializeTTS()
        }
    }

    private fun initializeTTS() {
        try {
            // 首先尝试 Pico TTS（通常在 MIUI 中可用）
            Log.d(TAG, "Attempting to initialize Pico TTS...")
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                handleInitializationStatus(status, "Default Engine")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS", e)
            isReady = false
            isSilentMode = true
            initializationError = "离线 TTS 引擎初始化失败，尝试切换到无声模式"
            lastSpeakError = "当前系统无法提供语音播报，但导盲识别功能正常。建议下载讯飞语音或百度语音应用以获得语音提醒。"
        }
    }

    private fun handleInitializationStatus(status: Int, engineName: String) {
        if (status == TextToSpeech.SUCCESS) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    currentEngine = engineName
                    configureEngine()
                    isReady = true
                    isSilentMode = false
                    initializationError = null
                    Log.d(TAG, "TTS engine '$engineName' initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to configure TTS engine", e)
                    isReady = false
                    isSilentMode = true
                    initializationError = "TTS 配置失败"
                    lastSpeakError = "当前系统无法提供语音播报，但导盲识别功能正常。建议下载讯飞语音或百度语音应用以获得语音提醒。"
                }
            }
        } else {
            isReady = false
            isSilentMode = true
            val errorMsg = describeInitializationError(status)
            initializationError = errorMsg
            lastSpeakError = "当前系统无法提供语音播报，但导盲识别功能正常。建议下载讯飞语音或百度语音应用以获得语音提醒。"
            Log.e(TAG, "TTS initialization failed: $errorMsg (status code: $status)")
        }
    }

    private fun configureEngine() {
        val engine = textToSpeech

        // 尝试设置中文
        val chineseLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA)
        var selectedLocale: Locale? = null

        for (locale in chineseLocales) {
            val availability = engine.isLanguageAvailable(locale)
            Log.d(TAG, "Language availability for $locale: $availability")

            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                selectedLocale = locale
                break
            } else if (availability == TextToSpeech.LANG_MISSING_DATA) {
                if (selectedLocale == null) {
                    selectedLocale = locale
                }
            }
        }

        // 如果找不到中文，降级到英文
        if (selectedLocale == null) {
            Log.w(TAG, "Chinese not available, fall back to English")
            selectedLocale = Locale.US
        }

        try {
            val languageResult = engine.setLanguage(selectedLocale)
            Log.d(TAG, "setLanguage($selectedLocale) returned: $languageResult")

            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)

            Log.d(TAG, "TTS engine configured with language: $selectedLocale (engine: $currentEngine)")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring language", e)
            throw e
        }
    }

    private fun describeInitializationError(status: Int): String {
        return when (status) {
            TextToSpeech.ERROR -> "离线 TTS 引擎初始化失败"
            TextToSpeech.ERROR_SERVICE -> "TTS 服务不可用"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "TTS 语音数据未安装"
            else -> "TTS 初始化失败（状态码：$status）"
        }
    }

    fun isReady(): Boolean = isReady || isSilentMode

    fun isSilentMode(): Boolean = isSilentMode

    fun getInitializationError(): String? = initializationError

    fun getLastSpeakError(): String? = lastSpeakError

    fun getCurrentEngine(): String = currentEngine

    fun speakNow(text: String, key: String = text): Boolean {
        if (text.isBlank()) return false
        if (!isReady && !isSilentMode) return false

        return speakInternal(key, text)
    }

    fun speakIfAllowed(
        key: String,
        text: String,
        cooldownMs: Long = 3000L,
        globalCooldownMs: Long = 1000L
    ) {
        if (text.isBlank()) return
        if (!isReady && !isSilentMode) return

        val now = System.currentTimeMillis()
        val lastKeyTime = lastSpokenAt[key] ?: 0L
        val lastText = lastSpokenText[key]
        
        if (now - lastKeyTime < cooldownMs && lastText == text) return

        speakInternal(key, text)
    }

    private fun speakInternal(key: String, text: String): Boolean {
        // 如果在无声模式，只记录日志
        if (isSilentMode) {
            Log.i(TAG, "[SILENT MODE] Would speak: $text (key: $key, engine: $currentEngine)")
            lastSpeakError = null
            return true
        }

        return try {
            if (!::textToSpeech.isInitialized) {
                lastSpeakError = "TTS 引擎未初始化"
                return false
            }

            val now = System.currentTimeMillis()
            lastSpokenAt[key] = now
            lastSpokenText[key] = text

            val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
            if (result == TextToSpeech.SUCCESS) {
                lastSpeakError = null
                Log.d(TAG, "Speaking: $text (key: $key, engine: $currentEngine)")
            } else {
                lastSpeakError = "TTS 播报失败（状态码：$result）"
                Log.w(TAG, "Failed to speak text: $text (result code: $result)")
            }
            result == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            lastSpeakError = "TTS 播报异常：${e.message}"
            Log.e(TAG, "Exception while speaking: $text", e)
            false
        }
    }

    override fun close() {
        try {
            if (::textToSpeech.isInitialized) {
                textToSpeech.stop()
                textToSpeech.shutdown()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TTS", e)
        }
    }
}



