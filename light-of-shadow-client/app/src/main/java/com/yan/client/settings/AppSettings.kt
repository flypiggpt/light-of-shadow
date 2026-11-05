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
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_IN = "expires_in"

    private lateinit var prefs: SharedPreferences

    private val safePrefs: SharedPreferences
        get() {
            check(::prefs.isInitialized) { "Call AppSettings.init(context) before use" }
            return prefs
        }

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var alertSoundEnabled: Boolean
        get() = safePrefs.getBoolean(KEY_ALERT_SOUND, true)
        set(value) = safePrefs.edit().putBoolean(KEY_ALERT_SOUND, value).apply()

    var ttsEnabled: Boolean
        get() = safePrefs.getBoolean(KEY_TTS, true)
        set(value) = safePrefs.edit().putBoolean(KEY_TTS, value).apply()

    var speechRate: Float
        get() = safePrefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = safePrefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.6f, 1.4f)).apply()

    var account: String
        get() = safePrefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) = safePrefs.edit().putString(KEY_ACCOUNT, value).apply()

    var nickname: String
        get() = safePrefs.getString(KEY_NICKNAME, "") ?: ""
        set(value) = safePrefs.edit().putString(KEY_NICKNAME, value).apply()

    fun saveLoginProfile(account: String, nickname: String?) {
        this.account = account
        this.nickname = nickname ?: ""
    }

    fun saveTokens(accessToken: String?, refreshToken: String?, expiresIn: Long) {
        TokenStore.accessToken = accessToken
        TokenStore.refreshToken = refreshToken
        TokenStore.expiresIn = expiresIn
        safePrefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken ?: "")
            .putString(KEY_REFRESH_TOKEN, refreshToken ?: "")
            .putLong(KEY_EXPIRES_IN, expiresIn)
            .apply()
    }

    /** 启动时从本地恢复登录态；返回是否有有效 token */
    fun restoreSession(): Boolean {
        val access = safePrefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
        val refresh = safePrefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        if (access.isBlank() && refresh.isBlank()) return false
        TokenStore.accessToken = access.ifBlank { null }
        TokenStore.refreshToken = refresh.ifBlank { null }
        TokenStore.expiresIn = safePrefs.getLong(KEY_EXPIRES_IN, 0L)
        return true
    }

    fun clearSession() {
        safePrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_IN)
            .apply()
        TokenStore.accessToken = null
        TokenStore.refreshToken = null
        TokenStore.expiresIn = 0L
        account = ""
        nickname = ""
    }
}
