package com.yan.client.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yan.client.ui.theme.DarkGoldBackground
import com.yan.client.ui.theme.DarkGoldCard
import com.yan.client.ui.theme.DarkGoldHighlight
import com.yan.client.ui.theme.DarkGoldPrimary
import com.yan.client.ui.theme.DarkGoldTextPrimary
import com.yan.client.ui.theme.DarkGoldTextSecondary

private val goldColors = @Composable {
    Triple(DarkGoldTextPrimary, DarkGoldTextSecondary, DarkGoldHighlight)
}

@Composable
internal fun ProductDetailContent(
    product: ShopProduct?,
    loading: Boolean,
    onBack: () -> Unit,
    onAddCart: () -> Unit
) {
    if (product == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(if (loading) "加载中…" else "商品不存在", color = DarkGoldTextSecondary)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = DarkGoldPrimary)) {
                Text("返回")
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("商品详情", style = MaterialTheme.typography.titleLarge, color = DarkGoldTextPrimary)
        }

        product.coverUrl?.let { url ->
            val full = resolveMediaUrl(url)
            if (full != null) {
                AsyncImage(
                    model = full, contentDescription = product.name,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Text(product.name, style = MaterialTheme.typography.headlineSmall, color = DarkGoldTextPrimary)
        Text(centsToPrice(product.priceCent), style = MaterialTheme.typography.headlineMedium, color = DarkGoldHighlight)
        Text("库存 ${product.stock}", color = DarkGoldTextSecondary)
        Text(product.description, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodyMedium)

        Button(
            onClick = onAddCart,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = product.stock > 0,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkGoldPrimary, contentColor = DarkGoldHighlight)
        ) {
            Text(if (product.stock > 0) "加入购物车" else "暂时缺货")
        }
    }
}

internal data class AddressItem(
    val id: String,
    val receiverName: String,
    val phone: String,
    val detail: String,
    val isDefault: Boolean
)

@Composable
internal fun AddressListContent(
    addresses: List<AddressItem>,
    selectedId: String?,
    onSelect: (AddressItem) -> Unit,
    onSave: (id: String?, name: String, phone: String, detail: String, isDefault: Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    var showForm by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("收货地址", style = MaterialTheme.typography.titleLarge, color = DarkGoldTextPrimary)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    editId = null; name = ""; phone = ""; detail = ""; isDefault = false
                    showForm = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = DarkGoldPrimary, contentColor = DarkGoldHighlight)
            ) { Text("新增") }
        }

        if (showForm) {
            Card(colors = CardDefaults.cardColors(containerColor = DarkGoldCard), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("收货人") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("手机号") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = detail, onValueChange = { detail = it }, label = { Text("详细地址") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("默认地址", color = DarkGoldTextSecondary)
                        Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (name.isNotBlank() && phone.isNotBlank() && detail.isNotBlank()) {
                                    onSave(editId, name.trim(), phone.trim(), detail.trim(), isDefault)
                                    showForm = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkGoldPrimary, contentColor = DarkGoldHighlight)
                        ) { Text("保存") }
                        OutlinedButton(onClick = { showForm = false }) { Text("取消") }
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(addresses, key = { it.id }) { addr ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkGoldCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = addr.id == selectedId, onClick = { onSelect(addr) })
                        Column(Modifier.weight(1f)) {
                            Text("${addr.receiverName}  ${addr.phone}", color = DarkGoldTextPrimary)
                            Text(addr.detail, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodySmall)
                            if (addr.isDefault) Text("默认", color = DarkGoldHighlight, style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = {
                            editId = addr.id; name = addr.receiverName; phone = addr.phone
                            detail = addr.detail; isDefault = addr.isDefault; showForm = true
                        }) { Text("编辑") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = { onDelete(addr.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

internal data class OrderItemView(val productId: String, val productName: String, val unitPriceCent: Long, val quantity: Int)

@Composable
internal fun OrderConfirmContent(
    items: List<OrderItemView>,
    totalCent: Long,
    addresses: List<AddressItem>,
    selectedAddressId: String?,
    onSelectAddress: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    submitting: Boolean
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("确认订单", style = MaterialTheme.typography.titleLarge, color = DarkGoldTextPrimary)
        }

        // 地址
        Card(colors = CardDefaults.cardColors(containerColor = DarkGoldCard), shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val addr = addresses.find { it.id == selectedAddressId }
                    if (addr != null) {
                        Text("${addr.receiverName}  ${addr.phone}", color = DarkGoldTextPrimary)
                        Text(addr.detail, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("请选择收货地址", color = DarkGoldTextSecondary)
                    }
                }
                OutlinedButton(onClick = onSelectAddress) { Text("选择地址") }
            }
        }

        // 商品清单
        Card(colors = CardDefaults.cardColors(containerColor = DarkGoldCard), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("商品清单", color = DarkGoldTextSecondary, style = MaterialTheme.typography.labelLarge)
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.productName} ×${item.quantity}", color = DarkGoldTextPrimary, modifier = Modifier.weight(1f))
                        Text(centsToPrice(item.unitPriceCent * item.quantity), color = DarkGoldTextSecondary)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("合计", color = DarkGoldTextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(centsToPrice(totalCent), color = DarkGoldHighlight, style = MaterialTheme.typography.titleMedium)
        }

        Button(
            onClick = onConfirm,
            enabled = selectedAddressId != null && !submitting,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkGoldPrimary, contentColor = DarkGoldHighlight)
        ) {
            Text(if (submitting) "提交中…" else "提交订单")
        }
    }
}

@Composable
internal fun OrderDetailContent(
    orderNo: String,
    status: String,
    totalCent: Long,
    remark: String?,
    items: List<OrderItemView>,
    loading: Boolean,
    canPay: Boolean,
    canCancel: Boolean,
    onPay: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("订单详情", style = MaterialTheme.typography.titleLarge, color = DarkGoldTextPrimary)
        }

        Card(colors = CardDefaults.cardColors(containerColor = DarkGoldCard), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("订单号：$orderNo", color = DarkGoldTextPrimary)
                Text("状态：${statusText(status)}", color = DarkGoldHighlight)
                Text("金额：${centsToPrice(totalCent)}", color = DarkGoldTextPrimary)
                if (!remark.isNullOrBlank()) Text("备注：$remark", color = DarkGoldTextSecondary)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = DarkGoldCard), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("商品", color = DarkGoldTextSecondary, style = MaterialTheme.typography.labelLarge)
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.productName} ×${item.quantity}", color = DarkGoldTextPrimary, modifier = Modifier.weight(1f))
                        Text(centsToPrice(item.unitPriceCent * item.quantity), color = DarkGoldTextSecondary)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (canPay) {
                Button(
                    onClick = onPay,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkGoldPrimary, contentColor = DarkGoldHighlight)
                ) { Text("立即支付") }
            }
            if (canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DarkGoldPrimary.copy(alpha = 0.6f))
                ) { Text("取消订单") }
            }
        }

        if (loading) Text("处理中…", color = DarkGoldTextSecondary)
    }
}

internal fun statusText(status: String): String = when (status) {
    "PENDING_PAYMENT" -> "待支付"
    "PAID" -> "已支付"
    "SHIPPED" -> "已发货"
    "COMPLETED" -> "已完成"
    "CANCELED" -> "已取消"
    else -> status
}
