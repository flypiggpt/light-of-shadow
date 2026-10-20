package com.yan.client.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.yan.client.audio.TtsAlertManager
import com.yan.client.settings.AppSettings
import com.yan.client.ui.theme.DarkGoldBackground
import com.yan.client.ui.theme.DarkGoldCard
import com.yan.client.ui.theme.DarkGoldHighlight
import com.yan.client.ui.theme.DarkGoldPrimary
import com.yan.client.ui.theme.DarkGoldTextPrimary
import com.yan.client.ui.theme.DarkGoldTextSecondary

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    var alertSound by remember { mutableStateOf(AppSettings.alertSoundEnabled) }
    var ttsEnabled by remember { mutableStateOf(AppSettings.ttsEnabled) }
    var speechRate by remember { mutableFloatStateOf(AppSettings.speechRate) }

    Scaffold(
        modifier = modifier,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = DarkGoldTextPrimary
                )
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkGoldCard,
                        contentColor = DarkGoldHighlight
                    )
                ) {
                    Text("返回")
                }
            }

            SettingsGroup(title = "导盲与语音") {
                SettingsSwitchRow(
                    title = "提示音",
                    subtitle = "检测到障碍时播放「叮」提示音",
                    checked = alertSound,
                    onCheckedChange = {
                        alertSound = it
                        AppSettings.alertSoundEnabled = it
                    }
                )
                SettingsSwitchRow(
                    title = "语音播报",
                    subtitle = "朗读障碍物名称（如：行人、车辆）",
                    checked = ttsEnabled,
                    onCheckedChange = {
                        ttsEnabled = it
                        AppSettings.ttsEnabled = it
                    }
                )
                SettingsSliderRow(
                    title = "语音语速",
                    value = speechRate,
                    onValueChange = { rate ->
                        speechRate = rate
                        AppSettings.speechRate = rate
                    }
                )
                val testTts = remember(context) { TtsAlertManager(context) }
                DisposableEffect(testTts) {
                    onDispose { testTts.close() }
                }
                Button(
                    onClick = {
                        testTts.setSpeechRate(AppSettings.speechRate)
                        val ok = testTts.speakNow("语音测试成功")
                        val msg = when {
                            ok -> "TTS 已触发播报"
                            testTts.getInitializationError() != null -> testTts.getInitializationError()!!
                            testTts.getLastSpeakError() != null -> testTts.getLastSpeakError()!!
                            else -> "TTS 未就绪，请稍候"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkGoldCard,
                        contentColor = DarkGoldHighlight
                    )
                ) {
                    Text("测试语音播报")
                }
            }

            SettingsGroup(title = "账号") {
                SettingsTextRow(
                    title = "当前账号",
                    value = AppSettings.account.ifBlank { "未登录" }
                )
                SettingsTextRow(
                    title = "昵称",
                    value = AppSettings.nickname.ifBlank { "—" }
                )
                Button(
                    onClick = {
                        AppSettings.clearSession()
                        onLogout()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkGoldCard,
                        contentColor = DarkGoldHighlight
                    ),
                    border = BorderStroke(1.dp, DarkGoldPrimary.copy(alpha = 0.7f))
                ) {
                    Text("退出登录")
                }
            }

            SettingsGroup(title = "关于") {
                SettingsTextRow(title = "版本", value = "1.0.0")
                SettingsTextRow(title = "协议", value = "Apache License 2.0")
                SettingsTextRow(
                    title = "简介",
                    value = "音影寻光：面向视障人群的辅助应用，本地识别障碍物并语音提醒，配套商城与 AI 导购。"
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = DarkGoldTextSecondary
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(20.dp))
                .background(DarkGoldCard, RoundedCornerShape(20.dp))
                .border(
                    BorderStroke(1.dp, DarkGoldPrimary.copy(alpha = 0.35f)),
                    RoundedCornerShape(20.dp)
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = DarkGoldTextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, color = DarkGoldTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkGoldHighlight,
                checkedTrackColor = DarkGoldPrimary.copy(alpha = 0.45f)
            )
        )
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, color = DarkGoldTextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = String.format("%.1fx", value),
                color = DarkGoldTextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.6f..1.4f,
            steps = 7
        )
    }
}

@Composable
private fun SettingsTextRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title,
            color = DarkGoldTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            color = DarkGoldTextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
