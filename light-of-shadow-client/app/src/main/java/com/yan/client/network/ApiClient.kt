package com.yan.client.network

import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.StringBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.net.URLEncoder

object ApiClient {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }
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
    /** 认证相关路径：401 时不走 refresh 重试，避免死循环 */
    private val noRetryPaths = setOf(
        "/auth/login", "/auth/register", "/auth/code",
        "/auth/password/forgot", "/auth/password/reset",
        "/auth/refresh", "/auth/logout"
    )

    @Throws(Exception::class)
    private fun request(
        method: String,
        path: String,
        jsonBody: JSONObject? = null,
        headers: Map<String, String> = emptyMap()
    ): ApiResponse {
        val resp = doRequest(method, path, jsonBody, headers)
        val tokenPath = path.substringBefore("?")
        val canRetry = tokenPath !in noRetryPaths &&
                resp.code == 401 &&
                !TokenStore.refreshToken.isNullOrBlank()
        if (!canRetry) return resp

        // accessToken 过期：用 refreshToken 换新后重试一次
        val refreshed = refresh(TokenStore.refreshToken!!)
        if (refreshed.code != 0) return resp

        val newHeaders = if (headers.keys.any { it.equals("Authorization", true) }) {
            headers.mapValues { (k, v) -> if (k.equals("Authorization", true)) "Bearer ${TokenStore.accessToken}" else v }
        } else {
            headers + getAuthHeaders()
        }
        return doRequest(method, path, jsonBody, newHeaders)
    }

    @Throws(Exception::class)
    private fun doRequest(
        method: String,
        path: String,
        jsonBody: JSONObject? = null,
        headers: Map<String, String> = emptyMap()
    ): ApiResponse {
        val url = "$API_BASE_URL$path"
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = when {
            method in listOf("POST", "PUT", "PATCH") && jsonBody != null ->
                jsonBody.toString().toRequestBody(jsonMediaType)
            method in listOf("POST", "PUT", "PATCH") ->
                "{}".toRequestBody(jsonMediaType)
            else -> null
        }

        val builder = Request.Builder().url(url)
        if (method !in listOf("POST", "PUT", "PATCH")) {
            builder.method(method, null)
        } else {
            builder.method(method, body)
        }
        builder.header("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (key, value) -> builder.header(key, value) }

        httpClient.newCall(builder.build()).execute().use { resp ->
            val httpCode = resp.code
            val respBody = resp.body?.string() ?: ""
            Log.d(TAG, "$method $path -> $httpCode: $respBody")

            return try {
                val parsed = JSONObject(respBody)
                val c = parsed.optInt("code", if (httpCode in 200..299) 0 else httpCode)
                val message = if (parsed.has("message") && !parsed.isNull("message")) parsed.optString("message") else null
                val data = if (parsed.has("data") && !parsed.isNull("data")) parsed.getJSONObject("data") else null
                ApiResponse(c, message, data)
            } catch (j: Exception) {
                ApiResponse(if (httpCode in 200..299) 0 else httpCode, respBody, null)
            }
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
    fun register(username: String, email: String, password: String, nickname: String? = null, verificationCode: String = ""): ApiResponse {
        val body = JSONObject().apply {
            put("username", username)
            put("email", email)
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
    fun resetPassword(email: String, verificationCode: String, newPassword: String): ApiResponse {
        val body = JSONObject().apply {
            put("email", email)
            put("verificationCode", verificationCode)
            put("newPassword", newPassword)
        }
        return request("POST", "/auth/password/reset", body)
    }

    /**
     * POST /api/auth/logout
     * 作废当前 refreshToken
     */
    @Throws(Exception::class)
    fun logout(): ApiResponse {
        val body = JSONObject().apply {
            put("refreshToken", TokenStore.refreshToken ?: "")
        }
        return request("POST", "/auth/logout", body)
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
        val resp = request("POST", "/auth/refresh", body)
        // 刷新成功后持久化新 token（含轮换的 refreshToken）
        if (resp.code == 0) {
            val data = resp.data
            com.yan.client.settings.AppSettings.saveTokens(
                accessToken = data?.optString("accessToken")?.takeIf { it.isNotBlank() },
                refreshToken = data?.optString("refreshToken")?.takeIf { it.isNotBlank() }
                    ?: TokenStore.refreshToken,
                expiresIn = data?.optLong("expiresIn", 0L) ?: 0L
            )
        }
        return resp
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
     * GET /api/shoppingguide/sessions
     * 最近一次会话（App 重启恢复历史）
     */
    @Throws(Exception::class)
    fun getLatestGuideSession(): ApiResponse {
        return get("/shoppingguide/sessions", getAuthHeaders())
    }

    /**
     * GET /api/shoppingguide/sessions/{sessionId}/messages
     */
    @Throws(Exception::class)
    fun getGuideMessages(sessionId: String, pageSize: Int = 100): ApiResponse {
        return get("/shoppingguide/sessions/$sessionId/messages?page=1&size=$pageSize", getAuthHeaders())
    }

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

    /**
     * POST /api/shoppingguide/sessions/{sessionId}/close
     * Close a shopping guide session (best-effort, new-chat flow).
     */
    @Throws(Exception::class)
    fun closeShoppingGuideSession(sessionId: String): ApiResponse {
        return post("/shoppingguide/sessions/$sessionId/close", JSONObject(), getAuthHeaders())
    }

    /**
     * GET /api/shop/orders/{id} 订单详情
     */
    @Throws(Exception::class)
    fun getOrderDetail(orderId: String): ApiResponse {
        return get("/shop/orders/$orderId", getAuthHeaders())
    }

    /**
     * POST /api/shop/orders/{id}/pay 模拟支付
     */
    @Throws(Exception::class)
    fun payOrder(orderId: String): ApiResponse {
        return post("/shop/orders/$orderId/pay", JSONObject(), getAuthHeaders())
    }

    /**
     * POST /api/shop/orders/{id}/cancel 取消订单
     */
    @Throws(Exception::class)
    fun cancelOrder(orderId: String): ApiResponse {
        return post("/shop/orders/$orderId/cancel", JSONObject(), getAuthHeaders())
    }

    /**
     * GET /api/shop/addresses
     */
    @Throws(Exception::class)
    fun listAddresses(): ApiResponse {
        return get("/shop/addresses", getAuthHeaders())
    }

    /**
     * POST /api/shop/addresses 新增/更新地址
     */
    @Throws(Exception::class)
    fun saveAddress(id: String?, receiverName: String, phone: String, detail: String, isDefault: Boolean): ApiResponse {
        val body = JSONObject().apply {
            if (id != null) put("id", id)
            put("receiverName", receiverName)
            put("phone", phone)
            put("detail", detail)
            put("isDefault", isDefault)
        }
        return post("/shop/addresses", body, getAuthHeaders())
    }

    /**
     * DELETE /api/shop/addresses/{id}
     */
    @Throws(Exception::class)
    fun deleteAddress(addressId: String): ApiResponse {
        return delete("/shop/addresses/$addressId", getAuthHeaders())
    }

    /**
     * GET /api/shop/products/{id} 商品详情
     */
    @Throws(Exception::class)
    fun getShopProduct(id: String): ApiResponse {
        return get("/shop/products/$id", getAuthHeaders())
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