package com.yan.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yan.client.ui.theme.DarkGoldBackground
import com.yan.client.ui.theme.DarkGoldCard
import com.yan.client.ui.theme.DarkGoldHighlight
import com.yan.client.ui.theme.DarkGoldPrimary
import com.yan.client.ui.theme.DarkGoldTextPrimary
import com.yan.client.ui.theme.DarkGoldTextSecondary
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onStartGuide: () -> Unit = {},
    onShopClick: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkGoldBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DarkGoldBackground, DarkGoldBackground.copy(alpha = 0.94f))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopActionCard(
                        text = "设置",
                        onClick = onSettingsClick
                    )

                    TopActionCard(
                        text = "退出",
                        onClick = {
                            onLogout()
                            scope.launch { snackbarHostState.showSnackbar("已登出") }
                        }
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    Text(
                        text = "音影 · 寻光",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = DarkGoldTextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "聆听世界 · 寻找光明",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DarkGoldTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    StartGuideButton(
                        onClick = onStartGuide
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    BottomPanelCard(
                        title = "商城",
                        subtitle = "SHOPS",
                        onClick = {
                            onShopClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopActionCard(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 86.dp, height = 86.dp)
            .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.34f))
            .background(DarkGoldCard, RoundedCornerShape(22.dp))
            .border(
                BorderStroke(1.2.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = DarkGoldHighlight,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun StartGuideButton(
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(228.dp)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.26f),
                spotColor = Color.Black.copy(alpha = 0.38f)
            )
            .background(DarkGoldCard, CircleShape)
            .border(
                BorderStroke(2.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                CircleShape
            )
            .clickable(onClick = onClick)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                repeat(5) { index ->
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = (16 + (index % 3) * 10).dp)
                            .background(DarkGoldHighlight, RoundedCornerShape(99.dp))
                    )
                }
            }
            Text(
                text = "开始导航",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = DarkGoldHighlight
            )
        }
    }
}

@Composable
private fun BottomPanelCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.32f))
            .background(DarkGoldCard, RoundedCornerShape(24.dp))
            .border(
                BorderStroke(1.2.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                    color = DarkGoldHighlight
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkGoldTextSecondary
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.headlineMedium,
                color = DarkGoldPrimary
            )
        }
    }
}
