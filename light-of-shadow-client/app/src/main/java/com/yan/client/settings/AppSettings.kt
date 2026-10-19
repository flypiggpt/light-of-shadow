package com.yan.client.settings

import android.content.Context
import android.content.SharedPreferences
import com.yan.client.network.TokenStore

/**
 * 应用设置持久化（SharedPreferences）。
 * 提示音 / 语音播报 / 语速 / 账号展示信息。
 */
object AppSettings {
    private const val PREFS = "app_settings"

    private const val KEY_ALERT_SOUND = "alert_sound_enabled"
    private const val KEY_TTS = "tts_enabled"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_NICKNAME = "nickname"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var alertSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERT_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_SOUND, value).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.6f, 1.4f)).apply()

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NICKNAME, value).apply()

    fun saveLoginProfile(account: String, nickname: String?) {
        this.account = account
        this.nickname = nickname ?: ""
    }

    fun clearSession() {
        TokenStore.accessToken = null
        TokenStore.refreshToken = null
        TokenStore.expiresIn = 0L
        account = ""
        nickname = ""
    }
}
