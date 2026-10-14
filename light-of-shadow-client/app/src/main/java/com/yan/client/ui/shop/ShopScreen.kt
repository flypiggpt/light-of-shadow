package com.yan.client.ui.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yan.client.R
import com.yan.client.network.ApiClient
import com.yan.client.ui.theme.DarkGoldBackground
import com.yan.client.ui.theme.DarkGoldCard
import com.yan.client.ui.theme.DarkGoldHighlight
import com.yan.client.ui.theme.DarkGoldPrimary
import com.yan.client.ui.theme.DarkGoldTextPrimary
import com.yan.client.ui.theme.DarkGoldTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

private enum class ShopTab {
    Products,
    Cart,
    Orders,
    Guide
}

private data class ShopProduct(
    val id: String,
    val name: String,
    val description: String,
    val priceCent: Long,
    val stock: Int,
    val coverUrl: String?
)

private data class CartLine(
    val itemId: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCent: Long
)

private data class ShopOrder(
    val orderId: String,
    val orderNo: String,
    val status: String,
    val totalCent: Long,
    val createdAt: String
)

private data class GuideMessage(
    val role: String,
    val content: String
)

private data class GuideRecommendation(
    val productId: String,
    val name: String,
    val description: String,
    val priceCent: Long,
    val stock: Int,
    val coverUrl: String?,
    val reason: String
)

private data class GuideAction(
    val type: String,
    val label: String,
    val productId: String?,
    val quantity: Int
)

@Composable
fun ShopScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by rememberSaveable { mutableStateOf(ShopTab.Products) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    var products by remember { mutableStateOf(emptyList<ShopProduct>()) }
    var cartLines by remember { mutableStateOf(emptyList<CartLine>()) }
    var orders by remember { mutableStateOf(emptyList<ShopOrder>()) }
    var guideSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var guideInput by rememberSaveable { mutableStateOf("") }
    var guideLoading by remember { mutableStateOf(false) }
    var guideMessages by remember { mutableStateOf(emptyList<GuideMessage>()) }
    var guideRecommendations by remember { mutableStateOf(emptyList<GuideRecommendation>()) }
    var guideActions by remember { mutableStateOf(emptyList<GuideAction>()) }

    suspend fun showMessage(message: String) {
        snackbarHostState.showSnackbar(message)
    }

    fun refreshProducts() {
        scope.launch {
            loading = true
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.getShopProducts(keyword = keyword.ifBlank { null })
                }
                if (resp.code == 0) {
                    products = parseProducts(resp.data)
                } else {
                    val errMsg = "加载商品失败: code=${resp.code}, message=${resp.message ?: "N/A"}"
                    android.util.Log.e("ShopScreen", errMsg)
                    showMessage(errMsg)
                }
            } catch (e: Exception) {
                android.util.Log.e("ShopScreen", "加载商品异常", e)
                showMessage("加载商品失败：${e.message ?: "未知错误"}")
            } finally {
                loading = false
            }
        }
    }

    fun refreshCart() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.getShopCart()
                }
                if (resp.code == 0) {
                    cartLines = parseCartLines(resp.data)
                } else {
                    val errMsg = "加载购物车失败: code=${resp.code}, message=${resp.message ?: "N/A"}"
                    android.util.Log.e("ShopScreen", errMsg)
                    showMessage(errMsg)
                }
            } catch (e: Exception) {
                android.util.Log.e("ShopScreen", "加载购物车异常", e)
                showMessage("加载购物车失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun refreshOrders() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.getShopOrders()
                }
                if (resp.code == 0) {
                    orders = parseOrders(resp.data)
                } else {
                    val errMsg = "加载订单失败: code=${resp.code}, message=${resp.message ?: "N/A"}"
                    android.util.Log.e("ShopScreen", errMsg)
                    showMessage(errMsg)
                }
            } catch (e: Exception) {
                android.util.Log.e("ShopScreen", "加载订单异常", e)
                showMessage("加载订单失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun addToCart(product: ShopProduct) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.addShopCartItem(productId = product.id, quantity = 1)
                }
                if (resp.code == 0) {
                    showMessage("已加入购物车：${product.name}")
                    refreshCart()
                } else {
                    showMessage(resp.message ?: "加入购物车失败 (code=${resp.code})")
                }
            } catch (e: Exception) {
                showMessage("加入购物车失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun removeCartItem(line: CartLine) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.removeShopCartItem(cartItemId = line.itemId)
                }
                if (resp.code == 0) {
                    showMessage("已移除：${line.productName}")
                    refreshCart()
                } else {
                    showMessage(resp.message ?: "移除失败 (code=${resp.code})")
                }
            } catch (e: Exception) {
                showMessage("移除失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun updateQuantity(line: CartLine, quantity: Int) {
        if (quantity <= 0) {
            removeCartItem(line)
            return
        }
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.updateShopCartItem(cartItemId = line.itemId, quantity = quantity)
                }
                if (resp.code == 0) {
                    refreshCart()
                } else {
                    showMessage(resp.message ?: "更新数量失败 (code=${resp.code})")
                }
            } catch (e: Exception) {
                showMessage("更新数量失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun checkout() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.checkoutShopCart()
                }
                if (resp.code == 0) {
                    showMessage("下单成功")
                    currentTab = ShopTab.Orders
                    refreshCart()
                    refreshOrders()
                } else {
                    showMessage(resp.message ?: "下单失败 (code=${resp.code})")
                }
            } catch (e: Exception) {
                showMessage("下单失败：${e.message ?: "未知错误"}")
            }
        }
    }

    suspend fun ensureGuideSessionId(): String? {
        if (!guideSessionId.isNullOrBlank()) return guideSessionId
        return try {
            val resp = withContext(Dispatchers.IO) {
                ApiClient.createShoppingGuideSession()
            }
            if (resp.code != 0) {
                showMessage(resp.message ?: "导购会话创建失败 (code=${resp.code})")
                null
            } else {
                val session = resp.data?.extractObject("session")
                val sid = session?.optString("sessionId")
                    ?.takeIf { it.isNotBlank() }
                    ?: resp.data?.optString("sessionId")?.takeIf { it.isNotBlank() }
                if (sid == null) {
                    showMessage("导购会话创建失败：缺少 sessionId")
                    null
                } else {
                    guideSessionId = sid
                    val welcome = resp.data?.optString("welcomeMessage")
                        ?.takeIf { it.isNotBlank() }
                    if (welcome != null && guideMessages.none { it.role == "assistant" && it.content == welcome }) {
                        guideMessages = guideMessages + GuideMessage(role = "assistant", content = welcome)
                    }
                    sid
                }
            }
        } catch (e: Exception) {
            showMessage("导购会话创建失败：${e.message ?: "未知错误"}")
            null
        }
    }

    fun sendGuideMessage(rawText: String = guideInput) {
        val message = rawText.trim()
        if (message.isBlank()) return

        scope.launch {
            guideLoading = true
            guideInput = ""
            guideMessages = guideMessages + GuideMessage(role = "user", content = message)
            try {
                val sid = ensureGuideSessionId()
                if (sid == null) return@launch
                val context = JSONObject().apply {
                    put("page", "ShopScreen")
                    put("selectedTab", "guide")
                }
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.sendShoppingGuideMessage(
                        sessionId = sid,
                        message = message,
                        clientContext = context
                    )
                }
                if (resp.code != 0) {
                    showMessage(resp.message ?: "导购请求失败 (code=${resp.code})")
                    return@launch
                }

                val assistantText = resp.data
                    ?.extractObject("assistantMessage")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: resp.data?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "我暂时没有找到完全匹配的商品，你可以再补充预算或偏好。"

                guideMessages = guideMessages + GuideMessage(role = "assistant", content = assistantText)
                guideRecommendations = parseGuideRecommendations(resp.data)
                guideActions = parseGuideActions(resp.data)
            } catch (e: Exception) {
                showMessage("导购请求失败：${e.message ?: "未知错误"}")
            } finally {
                guideLoading = false
            }
        }
    }

    fun onGuideAction(action: GuideAction) {
        when (action.type) {
            "add_to_cart" -> {
                val productId = action.productId ?: return
                val quantity = action.quantity.coerceAtLeast(1)
                scope.launch {
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            ApiClient.addShopCartItem(productId = productId, quantity = quantity)
                        }
                        if (resp.code == 0) {
                            showMessage("已加入购物车")
                            refreshCart()
                        } else {
                            showMessage(resp.message ?: "加入购物车失败 (code=${resp.code})")
                        }
                    } catch (e: Exception) {
                        showMessage("加入购物车失败：${e.message ?: "未知错误"}")
                    }
                }
            }

            "show_more" -> sendGuideMessage("请换一批推荐")
            "view_cart" -> currentTab = ShopTab.Cart
            else -> scope.launch {
                showMessage("该导购动作暂未接入：${action.label}")
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshProducts()
        refreshCart()
        refreshOrders()
    }

    LaunchedEffect(currentTab) {
        if (currentTab == ShopTab.Guide && guideSessionId.isNullOrBlank()) {
            ensureGuideSessionId()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkGoldBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DarkGoldBackground, DarkGoldBackground.copy(alpha = 0.95f))
                    )
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBar(onBack = onBack)

            SearchArea(
                keyword = keyword,
                onKeywordChange = { keyword = it },
                onSearchClick = { refreshProducts() }
            )

            TabBar(
                currentTab = currentTab,
                onSelect = { currentTab = it }
            )

            when (currentTab) {
                ShopTab.Products -> {
                    ProductList(
                        products = products,
                        loading = loading,
                        onRefresh = { refreshProducts() },
                        onAddCart = { addToCart(it) }
                    )
                }

                ShopTab.Cart -> {
                    CartList(
                        lines = cartLines,
                        onRefresh = { refreshCart() },
                        onIncrease = { updateQuantity(it, it.quantity + 1) },
                        onDecrease = { updateQuantity(it, it.quantity - 1) },
                        onRemove = { removeCartItem(it) },
                        onCheckout = { checkout() }
                    )
                }

                ShopTab.Orders -> {
                    OrderList(
                        orders = orders,
                        onRefresh = { refreshOrders() }
                    )
                }

                ShopTab.Guide -> {
                    ShoppingGuidePanel(
                        loading = guideLoading,
                        guideInput = guideInput,
                        onGuideInputChange = { guideInput = it },
                        onSend = { sendGuideMessage() },
                        messages = guideMessages,
                        recommendations = guideRecommendations,
                        actions = guideActions,
                        onActionClick = { onGuideAction(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text(text = stringResource(R.string.shop_back), color = DarkGoldPrimary)
        }

        Text(
            text = stringResource(R.string.shop_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = DarkGoldTextPrimary
        )

        Text(
            text = stringResource(R.string.shop_subtitle),
            color = DarkGoldTextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SearchArea(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearchClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .weight(1f)
                .background(DarkGoldCard, RoundedCornerShape(16.dp)),
            label = { Text(stringResource(R.string.shop_search_label), color = DarkGoldTextSecondary) },
            placeholder = { Text(stringResource(R.string.shop_search_hint), color = DarkGoldTextSecondary.copy(alpha = 0.75f)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DarkGoldPrimary,
                unfocusedBorderColor = DarkGoldPrimary.copy(alpha = 0.45f),
                focusedTextColor = DarkGoldTextPrimary,
                unfocusedTextColor = DarkGoldTextPrimary,
                cursorColor = DarkGoldPrimary,
                focusedContainerColor = DarkGoldCard,
                unfocusedContainerColor = DarkGoldCard
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        ActionButton(
            text = stringResource(R.string.shop_search_button),
            onClick = onSearchClick
        )
    }
}

@Composable
private fun TabBar(
    currentTab: ShopTab,
    onSelect: (ShopTab) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShopTab.entries.forEach { tab ->
            val selected = tab == currentTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(
                        if (selected) DarkGoldCard else DarkGoldCard.copy(alpha = 0.65f),
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        BorderStroke(
                            1.2.dp,
                            if (selected) Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))
                            else Brush.linearGradient(listOf(DarkGoldPrimary.copy(alpha = 0.4f), DarkGoldPrimary.copy(alpha = 0.4f)))
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (tab) {
                        ShopTab.Products -> stringResource(R.string.shop_tab_products)
                        ShopTab.Cart -> stringResource(R.string.shop_tab_cart)
                        ShopTab.Orders -> stringResource(R.string.shop_tab_orders)
                        ShopTab.Guide -> stringResource(R.string.shop_tab_guide)
                    },
                    color = if (selected) DarkGoldHighlight else DarkGoldTextSecondary,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
private fun ProductList(
    products: List<ShopProduct>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onAddCart: (ShopProduct) -> Unit
) {
    if (products.isEmpty()) {
        EmptyState(
            title = if (loading) stringResource(R.string.shop_loading) else stringResource(R.string.shop_empty_products),
            actionLabel = stringResource(R.string.shop_retry),
            onAction = onRefresh
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(products, key = { it.id }) { product ->
            GoldCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    product.coverUrl?.let { url ->
                        val fullUrl = resolveMediaUrl(url)
                        if (fullUrl == null) return@let
                        Log.d("ShopScreen", "Loading image for product ${product.id}: $fullUrl")
                        AsyncImage(
                            model = fullUrl,
                            contentDescription = product.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text(product.name, color = DarkGoldTextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(product.description, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = centsToPrice(product.priceCent),
                                color = DarkGoldHighlight,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = stringResource(R.string.shop_stock_template, product.stock),
                                color = DarkGoldTextSecondary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        ActionButton(text = stringResource(R.string.shop_add_cart), onClick = { onAddCart(product) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingGuidePanel(
    loading: Boolean,
    guideInput: String,
    onGuideInputChange: (String) -> Unit,
    onSend: () -> Unit,
    messages: List<GuideMessage>,
    recommendations: List<GuideRecommendation>,
    actions: List<GuideAction>,
    onActionClick: (GuideAction) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GoldCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.shop_guide_title),
                    color = DarkGoldTextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.shop_guide_hint),
                    color = DarkGoldTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { item ->
                GuideMessageBubble(message = item)
            }

            if (recommendations.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.shop_guide_recommendations),
                        color = DarkGoldHighlight,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(recommendations, key = { it.productId }) { rec ->
                    GoldCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rec.coverUrl?.let { url ->
                                val fullUrl = resolveMediaUrl(url)
                                if (fullUrl != null) {
                                    AsyncImage(
                                        model = fullUrl,
                                        contentDescription = rec.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            Text(rec.name, color = DarkGoldTextPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(rec.description, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodySmall)
                            if (rec.reason.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.shop_guide_reason_template, rec.reason),
                                    color = DarkGoldTextSecondary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = centsToPrice(rec.priceCent),
                                        color = DarkGoldHighlight,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = stringResource(R.string.shop_stock_template, rec.stock),
                                        color = DarkGoldTextSecondary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                ActionButton(
                                    text = stringResource(R.string.shop_add_cart),
                                    onClick = {
                                        onActionClick(
                                            GuideAction(
                                                type = "add_to_cart",
                                                label = "加入购物车",
                                                productId = rec.productId,
                                                quantity = 1
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (actions.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        actions.forEach { action ->
                            ActionButton(text = action.label, onClick = { onActionClick(action) })
                        }
                    }
                }
            }

            if (loading) {
                item {
                    Text(
                        text = stringResource(R.string.shop_guide_thinking),
                        color = DarkGoldTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = guideInput,
                onValueChange = onGuideInputChange,
                modifier = Modifier
                    .weight(1f)
                    .background(DarkGoldCard, RoundedCornerShape(16.dp)),
                placeholder = {
                    Text(
                        stringResource(R.string.shop_guide_input_hint),
                        color = DarkGoldTextSecondary.copy(alpha = 0.75f)
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DarkGoldPrimary,
                    unfocusedBorderColor = DarkGoldPrimary.copy(alpha = 0.45f),
                    focusedTextColor = DarkGoldTextPrimary,
                    unfocusedTextColor = DarkGoldTextPrimary,
                    cursorColor = DarkGoldPrimary,
                    focusedContainerColor = DarkGoldCard,
                    unfocusedContainerColor = DarkGoldCard
                )
            )

            ActionButton(
                text = stringResource(R.string.shop_guide_send),
                onClick = onSend
            )
        }
    }
}

@Composable
private fun GuideMessageBubble(message: GuideMessage) {
    val isUser = message.role == "user"
    val bg = if (isUser) DarkGoldPrimary.copy(alpha = 0.2f) else DarkGoldCard
    val textColor = if (isUser) DarkGoldTextPrimary else DarkGoldTextSecondary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(bg, RoundedCornerShape(14.dp))
                .border(
                    BorderStroke(1.dp, DarkGoldPrimary.copy(alpha = 0.55f)),
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CartList(
    lines: List<CartLine>,
    onRefresh: () -> Unit,
    onIncrease: (CartLine) -> Unit,
    onDecrease: (CartLine) -> Unit,
    onRemove: (CartLine) -> Unit,
    onCheckout: () -> Unit
) {
    if (lines.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.shop_empty_cart),
            actionLabel = stringResource(R.string.shop_refresh_cart),
            onAction = onRefresh
        )
        return
    }

    val total = lines.sumOf { it.unitPriceCent * it.quantity }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(lines, key = { it.itemId }) { line ->
            GoldCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(line.productName, color = DarkGoldTextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(centsToPrice(line.unitPriceCent), color = DarkGoldHighlight, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuantityChip(text = "-", onClick = { onDecrease(line) })
                        Text(line.quantity.toString(), color = DarkGoldTextPrimary)
                        QuantityChip(text = "+", onClick = { onIncrease(line) })
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemove(line) }) {
                            Text(stringResource(R.string.shop_remove), color = DarkGoldPrimary)
                        }
                    }
                }
            }
        }

        item {
            GoldCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.shop_total_template, centsToPrice(total)),
                        style = MaterialTheme.typography.titleMedium,
                        color = DarkGoldHighlight
                    )
                    ActionButton(
                        text = stringResource(R.string.shop_checkout_button),
                        onClick = onCheckout
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderList(
    orders: List<ShopOrder>,
    onRefresh: () -> Unit
) {
    if (orders.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.shop_empty_orders),
            actionLabel = stringResource(R.string.shop_refresh_orders),
            onAction = onRefresh
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(orders, key = { it.orderId }) { order ->
            GoldCard {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = stringResource(R.string.shop_order_no_template, order.orderNo),
                        color = DarkGoldTextPrimary,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.shop_order_status_template, order.status),
                        color = DarkGoldTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.shop_order_amount_template, centsToPrice(order.totalCent)),
                        color = DarkGoldHighlight,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(R.string.shop_order_time_template, order.createdAt),
                        color = DarkGoldTextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.shop_refresh_orders), color = DarkGoldPrimary)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GoldCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodyLarge)
                ActionButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
private fun GoldCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.32f))
            .background(DarkGoldCard, RoundedCornerShape(20.dp))
            .border(
                BorderStroke(1.2.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                RoundedCornerShape(20.dp)
            )
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DarkGoldCard,
            contentColor = DarkGoldHighlight
        ),
        border = BorderStroke(1.2.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(text = text)
    }
}

@Composable
private fun QuantityChip(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .background(DarkGoldCard, RoundedCornerShape(8.dp))
            .border(
                BorderStroke(1.dp, DarkGoldPrimary.copy(alpha = 0.7f)),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = DarkGoldHighlight, style = MaterialTheme.typography.titleSmall)
    }
}

private fun parseProducts(data: JSONObject?): List<ShopProduct> {
    val list = mutableListOf<ShopProduct>()
    val items = data.extractArray("items") ?: data.extractArray("list") ?: data.extractArray("records")
    if (items != null) {
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val product = ShopProduct(
                id = obj.optString("id", obj.optString("productId", "")),
                name = obj.optString("name", obj.optString("title", "未命名商品")),
                description = obj.optString("description", obj.optString("subtitle", "暂无描述")),
                priceCent = obj.optLong("priceCent", obj.optLong("price", 0L)),
                stock = obj.optInt("stock", obj.optInt("inventory", 0)),
                coverUrl = obj.optString("coverUrl").takeIf { it.isNotBlank() }
            )
            Log.d("ShopScreen", "Parsed product ${product.id}, coverUrl: ${product.coverUrl}")
            list += product
        }
    }
    return list.filter { it.id.isNotBlank() }
}

private fun parseCartLines(data: JSONObject?): List<CartLine> {
    val list = mutableListOf<CartLine>()
    val items = data.extractArray("items") ?: data.extractArray("cartItems")
    if (items != null) {
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            list += CartLine(
                itemId = obj.optString("itemId", obj.optString("id", "")),
                productId = obj.optString("productId", ""),
                productName = obj.optString("productName", obj.optString("name", "未知商品")),
                quantity = obj.optInt("quantity", 1),
                unitPriceCent = obj.optLong("unitPriceCent", obj.optLong("priceCent", 0L))
            )
        }
    }
    return list.filter { it.itemId.isNotBlank() }
}

private fun parseOrders(data: JSONObject?): List<ShopOrder> {
    val list = mutableListOf<ShopOrder>()
    val items = data.extractArray("items") ?: data.extractArray("list") ?: data.extractArray("orders")
    if (items != null) {
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            list += ShopOrder(
                orderId = obj.optString("orderId", obj.optString("id", "")),
                orderNo = obj.optString("orderNo", obj.optString("no", "-")),
                status = obj.optString("status", "UNKNOWN"),
                totalCent = obj.optLong("totalCent", obj.optLong("amountCent", 0L)),
                createdAt = obj.optString("createdAt", "")
            )
        }
    }
    return list.filter { it.orderId.isNotBlank() }
}

private fun parseGuideRecommendations(data: JSONObject?): List<GuideRecommendation> {
    val list = mutableListOf<GuideRecommendation>()
    val items = data.extractArray("recommendations") ?: data.extractArray("items")
    if (items != null) {
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            list += GuideRecommendation(
                productId = obj.optString("productId", obj.optString("id", "")),
                name = obj.optString("name", "未命名商品"),
                description = obj.optString("description", "暂无描述"),
                priceCent = obj.optLong("priceCent", 0L),
                stock = obj.optInt("stock", 0),
                coverUrl = obj.optString("coverUrl").takeIf { it.isNotBlank() },
                reason = obj.optString("reason", "")
            )
        }
    }
    return list.filter { it.productId.isNotBlank() }
}

private fun parseGuideActions(data: JSONObject?): List<GuideAction> {
    val list = mutableListOf<GuideAction>()
    val actions = data.extractArray("actions")
    if (actions != null) {
        for (i in 0 until actions.length()) {
            val obj = actions.optJSONObject(i) ?: continue
            list += GuideAction(
                type = obj.optString("type", ""),
                label = obj.optString("label", "执行"),
                productId = obj.optString("productId").takeIf { it.isNotBlank() },
                quantity = obj.optInt("quantity", 1)
            )
        }
    }
    return list
}

private fun JSONObject?.extractArray(key: String): JSONArray? {
    if (this == null) return null
    if (!this.has(key) || this.isNull(key)) return null
    return this.optJSONArray(key)
}

private fun JSONObject?.extractObject(key: String): JSONObject? {
    if (this == null) return null
    if (!this.has(key) || this.isNull(key)) return null
    return this.optJSONObject(key)
}

private fun resolveMediaUrl(url: String?): String? {
    val normalized = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
        normalized
    } else {
        ApiClient.MEDIA_BASE_URL + normalized
    }
}

private fun centsToPrice(cents: Long): String {
    return "¥%.2f".format(cents / 100.0)
}