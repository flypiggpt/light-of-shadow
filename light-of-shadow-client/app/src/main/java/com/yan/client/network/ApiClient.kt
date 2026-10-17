package com.yan.client.network

import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ApiClient {
    private const val TAG = "ApiClient"
    private const val API_BASE_URL = "http://132.232.75.62:8080/api"
    const val MEDIA_BASE_URL = "http://132.232.75.62:8080"

    data class ApiResponse(val code: Int, val message: String?, val data: JSONObject?)

    /**
     * Generic HTTP request method supporting all REST verbs.
     * 
     * @param method HTTP method (GET, POST, PUT, DELETE, PATCH)
     * @param path API endpoint path
     * @param jsonBody optional JSON body for POST/PUT/PATCH requests
     * @param headers optional custom headers
     * @return ApiResponse with code, message, and data
     */
    @Throws(Exception::class)
    private fun request(
        method: String,
        path: String,
        jsonBody: JSONObject? = null,
        headers: Map<String, String> = emptyMap()
    ): ApiResponse {
        val url = URL("$API_BASE_URL$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            
            // Add custom headers
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
            
            // Only set doOutput for methods that have a body
            if (method in listOf("POST", "PUT", "PATCH") && jsonBody != null) {
                doOutput = true
            }
        }

        try {
            // Write body if present
            if (jsonBody != null && method in listOf("POST", "PUT", "PATCH")) {
                BufferedOutputStream(conn.outputStream).use { out ->
                    val bytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    out.flush()
                }
            }

            val httpCode = conn.responseCode
            val reader = if (httpCode in 200..299) BufferedReader(InputStreamReader(conn.inputStream))
            else BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))

            val sb = StringBuilder()
            reader.use {
                var line: String? = it.readLine()
                while (line != null) {
                    sb.append(line)
                    line = it.readLine()
                }
            }

            val body = sb.toString()
            Log.d(TAG, "$method $path -> $httpCode: $body")

            return try {
                val parsed = JSONObject(body)
                val c = parsed.optInt("code", if (httpCode in 200..299) 0 else httpCode)
                val message = if (parsed.has("message") && !parsed.isNull("message")) parsed.optString("message") else null
                val data = if (parsed.has("data") && !parsed.isNull("data")) parsed.getJSONObject("data") else null
                ApiResponse(c, message, data)
            } catch (j: Exception) {
                // not JSON
                ApiResponse(if (httpCode in 200..299) 0 else httpCode, body, null)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ===================== Auth Endpoints =====================

    /**
     * POST /api/auth/login
     * Authenticate user with account and password
     */
    @Throws(Exception::class)
    fun login(account: String, password: String): ApiResponse {
        val body = JSONObject().apply {
            put("account", account)
            put("password", password)
        }
        return request("POST", "/auth/login", body)
    }

    /**
     * POST /api/auth/login  （验证码登录）
     */
    @Throws(Exception::class)
    fun loginWithCode(account: String, verificationCode: String): ApiResponse {
        val body = JSONObject().apply {
            put("account", account)
            put("verificationCode", verificationCode)
        }
        return request("POST", "/auth/login", body)
    }

    /**
     * POST /api/auth/register
     * Create a new user account
     */
    @Throws(Exception::class)
    fun register(account: String, password: String, nickname: String? = null, verificationCode: String = ""): ApiResponse {
        val body = JSONObject().apply {
            put("account", account)
            put("password", password)
            if (!nickname.isNullOrBlank()) put("nickname", nickname)
            put("verificationCode", verificationCode)
        }
        return request("POST", "/auth/register", body)
    }

    /**
     * POST /api/auth/code
     * scene: register | login | reset
     */
    @Throws(Exception::class)
    fun sendCode(target: String, scene: String): ApiResponse {
        val body = JSONObject().apply {
            put("channel", "email")
            put("target", target)
            put("scene", scene)
        }
        return request("POST", "/auth/code", body)
    }

    /**
     * POST /api/auth/password/forgot
     * Request password reset verification code
     */
    @Throws(Exception::class)
    fun forgotPassword(account: String): ApiResponse {
        val body = JSONObject().apply {
            put("account", account)
            put("scene", "reset_password")
        }
        return request("POST", "/auth/password/forgot", body)
    }

    /**
     * POST /api/auth/password/reset
     * Reset password with verification code
     */
    @Throws(Exception::class)
    fun resetPassword(account: String, verificationCode: String, newPassword: String): ApiResponse {
        val body = JSONObject().apply {
            put("account", account)
            put("verificationCode", verificationCode)
            put("newPassword", newPassword)
        }
        return request("POST", "/auth/password/reset", body)
    }

    /**
     * POST /api/auth/refresh
     * Refresh access token using refresh token
     */
    @Throws(Exception::class)
    fun refresh(refreshToken: String): ApiResponse {
        val body = JSONObject().apply {
            put("refreshToken", refreshToken)
        }
        return request("POST", "/auth/refresh", body)
    }

    // ===================== Shop Endpoints =====================

    /**
     * GET /api/shop/products
     * Query product list
     */
    @Throws(Exception::class)
    fun getShopProducts(keyword: String? = null, page: Int = 1, size: Int = 20): ApiResponse {
        val query = buildString {
            append("/shop/products?page=$page&size=$size")
            if (!keyword.isNullOrBlank()) {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                append("&keyword=$encoded")
            }
        }
        return get(query, getAuthHeaders())
    }

    /**
     * GET /api/shop/cart
     * Get current user's cart details
     */
    @Throws(Exception::class)
    fun getShopCart(): ApiResponse {
        return get("/shop/cart", getAuthHeaders())
    }

    /**
     * POST /api/shop/cart/items
     * Add item into cart
     */
    @Throws(Exception::class)
    fun addShopCartItem(productId: String, quantity: Int): ApiResponse {
        val body = JSONObject().apply {
            put("productId", productId)
            put("quantity", quantity)
        }
        return post("/shop/cart/items", body, getAuthHeaders())
    }

    /**
     * PATCH /api/shop/cart/items/{itemId}
     * Update cart item quantity
     */
    @Throws(Exception::class)
    fun updateShopCartItem(cartItemId: String, quantity: Int): ApiResponse {
        val body = JSONObject().apply {
            put("quantity", quantity)
        }
        return patch("/shop/cart/items/$cartItemId", body, getAuthHeaders())
    }

    /**
     * DELETE /api/shop/cart/items/{itemId}
     * Remove item from cart
     */
    @Throws(Exception::class)
    fun removeShopCartItem(cartItemId: String): ApiResponse {
        return delete("/shop/cart/items/$cartItemId", getAuthHeaders())
    }

    /**
     * POST /api/shop/orders
     * Checkout cart and create order
     */
    @Throws(Exception::class)
    fun checkoutShopCart(addressId: String? = null, remark: String? = null): ApiResponse {
        val body = JSONObject().apply {
            if (!addressId.isNullOrBlank()) put("addressId", addressId)
            if (!remark.isNullOrBlank()) put("remark", remark)
        }
        return post("/shop/orders", body, getAuthHeaders())
    }

    /**
     * GET /api/shop/orders
     * Query order history
     */
    @Throws(Exception::class)
    fun getShopOrders(status: String? = null, page: Int = 1, size: Int = 20): ApiResponse {
        val query = buildString {
            append("/shop/orders?page=$page&size=$size")
            if (!status.isNullOrBlank()) {
                val encoded = URLEncoder.encode(status, "UTF-8")
                append("&status=$encoded")
            }
        }
        return get(query, getAuthHeaders())
    }

    // ===================== Shopping Guide Endpoints =====================

    /**
     * POST /api/shoppingguide/sessions
     * Create a shopping guide session.
     */
    @Throws(Exception::class)
    fun createShoppingGuideSession(
        entry: String = "shop",
        source: String = "ShopScreen",
        locale: String = "zh-CN"
    ): ApiResponse {
        val body = JSONObject().apply {
            put("entry", entry)
            put("source", source)
            put("locale", locale)
        }
        return post("/shoppingguide/sessions", body, getAuthHeaders())
    }

    /**
     * POST /api/shoppingguide/sessions/{sessionId}/messages
     * Send user message and get assistant reply/recommendations.
     */
    @Throws(Exception::class)
    fun sendShoppingGuideMessage(
        sessionId: String,
        message: String,
        clientContext: JSONObject? = null
    ): ApiResponse {
        val body = JSONObject().apply {
            put("message", message)
            if (clientContext != null) put("clientContext", clientContext)
        }
        return post("/shoppingguide/sessions/$sessionId/messages", body, getAuthHeaders())
    }

    // ===================== Utility Methods =====================

    /**
     * Add Authorization Bearer token to default headers
     */
    fun getAuthHeaders(): Map<String, String> {
        return if (TokenStore.accessToken != null) {
            mapOf("Authorization" to "Bearer ${TokenStore.accessToken}")
        } else {
            emptyMap()
        }
    }

    /**
     * GET request helper
     */
    @Throws(Exception::class)
    fun get(path: String, headers: Map<String, String> = emptyMap()): ApiResponse {
        return request("GET", path, null, headers)
    }

    /**
     * POST request helper
     */
    @Throws(Exception::class)
    fun post(path: String, jsonBody: JSONObject, headers: Map<String, String> = emptyMap()): ApiResponse {
        return request("POST", path, jsonBody, headers)
    }

    /**
     * PUT request helper
     */
    @Throws(Exception::class)
    fun put(path: String, jsonBody: JSONObject, headers: Map<String, String> = emptyMap()): ApiResponse {
        return request("PUT", path, jsonBody, headers)
    }

    /**
     * PATCH request helper
     */
    @Throws(Exception::class)
    fun patch(path: String, jsonBody: JSONObject, headers: Map<String, String> = emptyMap()): ApiResponse {
        return request("PATCH", path, jsonBody, headers)
    }

    /**
     * DELETE request helper
     */
    @Throws(Exception::class)
    fun delete(path: String, headers: Map<String, String> = emptyMap()): ApiResponse {
        return request("DELETE", path, null, headers)
    }
}