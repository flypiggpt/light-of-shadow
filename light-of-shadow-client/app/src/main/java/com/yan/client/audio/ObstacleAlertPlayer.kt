package com.yan.client.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ObstacleAlertPlayer(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "ObstacleAlertPlayer"
        private const val ALERT_ASSET_FILE = "obstacle_alert.mp3"
        private const val DEFAULT_COOLDOWN_MS = 1800L
    }

    private val appContext = context.applicationContext
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var initializationError: String? = null
    @Volatile private var lastPlayedAt = 0L
    private val isClosed = AtomicBoolean(false)
    private val isSoundEnabled = AtomicBoolean(true)

    init {
        thread(isDaemon = true, start = true) {
            preparePlayer()
        }
    }

    fun isReady(): Boolean = mediaPlayer != null && initializationError == null

    fun getInitializationError(): String? = initializationError

    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled.set(enabled)
    }

    fun isSoundEnabled(): Boolean = isSoundEnabled.get()

    fun playIfAllowed(cooldownMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        if (isClosed.get()) return false

        val now = System.currentTimeMillis()
        if (now - lastPlayedAt < cooldownMs) return false
        return playNow()
    }

    @Synchronized
    fun playNow(force: Boolean = false): Boolean {
        if (isClosed.get()) return false

        if (!force && !isSoundEnabled.get()) {
            Log.d(TAG, "Alert sound is disabled, skipping playback.")
            return false
        }

        val player = mediaPlayer ?: run {
            if (initializationError == null) {
                initializationError = "本地提示音尚未准备好"
            }
            return false
        }

        return try {
            player.seekTo(0)
            player.start()
            lastPlayedAt = System.currentTimeMillis()
            Log.d(TAG, "Playing local alert sound: $ALERT_ASSET_FILE (forced: $force)")
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start local alert sound", e)
            initializationError = "本地提示音播放失败，请确认 $ALERT_ASSET_FILE 可用"
            false
        }
    }

    private fun preparePlayer() {
        try {
            if (isClosed.get()) return

            val afd = appContext.assets.openFd(ALERT_ASSET_FILE)
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = false
                setOnCompletionListener { mp ->
                    try {
                        mp.seekTo(0)
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
                setOnErrorListener { _, what, extra ->
                    initializationError = "本地提示音播放出错（what=$what, extra=$extra）"
                    Log.e(TAG, initializationError ?: "本地提示音播放出错")
                    true
                }
                prepare()
            }
            afd.close()

            if (!isClosed.get()) {
                mediaPlayer = player
                initializationError = null
                Log.d(TAG, "Local alert sound prepared successfully: $ALERT_ASSET_FILE")
            } else {
                player.release()
            }
        } catch (e: IOException) {
            initializationError = "未找到本地提示音文件 $ALERT_ASSET_FILE，请把 mp3 放到 app/src/main/assets/"
            Log.e(TAG, initializationError ?: "本地提示音文件缺失", e)
        } catch (e: Throwable) {
            initializationError = "本地提示音初始化失败：${e.message ?: "未知错误"}"
            Log.e(TAG, "Failed to prepare local alert sound", e)
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {
            // ignore
        }
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
            // ignore
        } finally {
            mediaPlayer = null
        }
    }
}