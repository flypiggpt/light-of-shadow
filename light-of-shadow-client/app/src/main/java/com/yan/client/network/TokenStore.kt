package com.yan.client.network

/**
 * Simple in-memory token holder. Replace with DataStore/SharedPreferences persistence as needed.
 */
object TokenStore {
    @Volatile
    var accessToken: String? = null
    @Volatile
    var refreshToken: String? = null
    @Volatile
    var expiresIn: Long = 0L
}
