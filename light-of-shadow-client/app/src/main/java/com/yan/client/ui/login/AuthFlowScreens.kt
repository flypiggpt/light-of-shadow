package com.yan.client.ui.login

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yan.client.R
import com.yan.client.ui.theme.DarkGoldBackground
import com.yan.client.ui.theme.DarkGoldCard
import com.yan.client.ui.theme.DarkGoldHighlight
import com.yan.client.ui.theme.DarkGoldPrimary
import com.yan.client.ui.theme.DarkGoldTextPrimary
import com.yan.client.ui.theme.DarkGoldTextSecondary
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yan.client.network.ApiClient

@Composable
fun RegisterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRegisterSuccess: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var verificationCode by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkGoldBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DarkGoldBackground, DarkGoldBackground.copy(alpha = 0.95f))
                    )
                )
                .padding(innerPadding)
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DarkGoldAccentBar()

                AuthTopBar(
                    title = stringResource(R.string.register_title),
                    onBack = onBack
                )

                AuthCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthInput(
                            label = stringResource(R.string.username_label),
                            value = username,
                            placeholder = stringResource(R.string.username_placeholder),
                            onValueChange = { username = it }
                        )
                        AuthInput(
                            label = stringResource(R.string.email_label),
                            value = email,
                            placeholder = stringResource(R.string.email_placeholder),
                            onValueChange = { email = it },
                            keyboardType = KeyboardType.Email
                        )
                        AuthInput(
                            label = stringResource(R.string.nickname_label),
                            value = nickname,
                            placeholder = stringResource(R.string.nickname_placeholder),
                            onValueChange = { nickname = it }
                        )
                        AuthInput(
                            label = stringResource(R.string.password_label),
                            value = password,
                            placeholder = stringResource(R.string.password_placeholder),
                            onValueChange = { password = it },
                            isPassword = true,
                            keyboardType = KeyboardType.Password
                        )
                        AuthInput(
                            label = stringResource(R.string.confirm_password_label),
                            value = confirmPassword,
                            placeholder = stringResource(R.string.confirm_password_placeholder),
                            onValueChange = { confirmPassword = it },
                            isPassword = true,
                            keyboardType = KeyboardType.Password
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AuthInput(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.verification_code_label),
                                value = verificationCode,
                                placeholder = stringResource(R.string.verification_code_placeholder),
                                onValueChange = { verificationCode = it },
                                keyboardType = KeyboardType.Number
                            )
                            Button(
                                onClick = {
                                    when {
                                        email.isBlank() -> {
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.email_required_hint)) }
                                            return@Button
                                        }
                                        !isValidEmail(email) -> {
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.email_invalid_hint)) }
                                            return@Button
                                        }
                                    }
                                    scope.launch {
                                        try {
                                            val resp = withContext(Dispatchers.IO) {
                                                ApiClient.sendCode(email.trim(), "register")
                                            }
                                            if (resp.code == 0) {
                                                snackbarHostState.showSnackbar(context.getString(R.string.code_sent_success))
                                            } else {
                                                snackbarHostState.showSnackbar(resp.message ?: "发送失败")
                                            }
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("网络错误：${e.message ?: "未知错误"}")
                                        }
                                    }
                                },
                                modifier = Modifier.padding(top = 20.dp)
                            ) {
                                Text(stringResource(R.string.send_code_button))
                            }
                        }

                        Button(
                            onClick = {
                                when {
                                    username.isBlank() || email.isBlank() || nickname.isBlank() || password.isBlank() || confirmPassword.isBlank() -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.empty_register_fields_hint))
                                        }
                                    }

                                    !isUsername(username) -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.username_invalid_hint))
                                        }
                                    }

                                    !isValidEmail(email) -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.email_invalid_hint))
                                        }
                                    }

                                    verificationCode.isBlank() -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.register_need_code_hint))
                                        }
                                    }

                                    password != confirmPassword -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.password_not_match_hint))
                                        }
                                    }

                                    else -> {
                                        // call backend register
                                        scope.launch {
                                            try {
                                                val resp = withContext(Dispatchers.IO) {
                                                    ApiClient.register(username.trim(), email.trim(), password, nickname.trim(), verificationCode.trim())
                                                }
                                                if (resp.code == 0) {
                                                    Toast.makeText(context, context.getString(R.string.register_success), Toast.LENGTH_SHORT).show()
                                                    onRegisterSuccess()
                                                } else {
                                                    val msg = resp.message ?: "注册失败 (code=${resp.code})"
                                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                                }
                                            } catch (e: Exception) {
                                                scope.launch { snackbarHostState.showSnackbar("网络错误：${e.message ?: "未知错误"}") }
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .border(
                                    BorderStroke(1.6.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                                    RoundedCornerShape(18.dp)
                                ),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkGoldCard,
                                contentColor = DarkGoldHighlight,
                                disabledContainerColor = DarkGoldCard.copy(alpha = 0.65f),
                                disabledContentColor = DarkGoldTextSecondary
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.complete_register_button),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class ForgotStep {
    SendCode,
    ResetPassword
}

@Composable
fun ForgotPasswordScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onResetSuccess: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var step by rememberSaveable { mutableStateOf(ForgotStep.SendCode) }
    var account by rememberSaveable { mutableStateOf("") }
    var verificationCode by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DarkGoldBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DarkGoldBackground, DarkGoldBackground.copy(alpha = 0.95f))
                    )
                )
                .padding(innerPadding)
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DarkGoldAccentBar()

                AuthTopBar(
                    title = stringResource(R.string.forgot_title),
                    onBack = onBack
                )

                AuthCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthInput(
                            label = stringResource(R.string.email_label),
                            value = account,
                            placeholder = stringResource(R.string.email_placeholder),
                            onValueChange = { account = it },
                            keyboardType = KeyboardType.Email
                        )

                        if (step == ForgotStep.SendCode) {
                            Button(
                                onClick = {
                                    if (account.isBlank()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.account_required_hint))
                                        }
                                    } else {
                                        scope.launch {
                                            try {
                                                val resp = withContext(Dispatchers.IO) {
                                                    ApiClient.forgotPassword(account.trim())
                                                }
                                                if (resp.code == 0) {
                                                    step = ForgotStep.ResetPassword
                                                    snackbarHostState.showSnackbar(context.getString(R.string.code_sent_success))
                                                } else {
                                                    val msg = resp.message ?: "发送验证码失败 (code=${resp.code})"
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar("网络错误：${e.message ?: "未知错误"}")
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .border(
                                        BorderStroke(1.6.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                                        RoundedCornerShape(18.dp)
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkGoldCard,
                                    contentColor = DarkGoldHighlight
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.send_code_button),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        } else {
                            AuthInput(
                                label = stringResource(R.string.verification_code_label),
                                value = verificationCode,
                                placeholder = stringResource(R.string.verification_code_placeholder),
                                onValueChange = { verificationCode = it },
                                keyboardType = KeyboardType.Number
                            )
                            AuthInput(
                                label = stringResource(R.string.new_password_label),
                                value = newPassword,
                                placeholder = stringResource(R.string.new_password_placeholder),
                                onValueChange = { newPassword = it },
                                isPassword = true,
                                keyboardType = KeyboardType.Password
                            )

                            Button(
                                onClick = {
                                    if (verificationCode.isBlank() || newPassword.isBlank()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.reset_fields_required_hint))
                                        }
                                    } else {
                                        scope.launch {
                                            try {
                                                val resp = withContext(Dispatchers.IO) {
                                                    ApiClient.resetPassword(account.trim(), verificationCode.trim(), newPassword)
                                                }
                                                if (resp.code == 0) {
                                                    Toast.makeText(context, context.getString(R.string.reset_password_success), Toast.LENGTH_SHORT).show()
                                                    onResetSuccess()
                                                } else {
                                                    val msg = resp.message ?: "密码重置失败 (code=${resp.code})"
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar("网络错误：${e.message ?: "未知错误"}")
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .border(
                                        BorderStroke(1.6.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                                        RoundedCornerShape(18.dp)
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkGoldCard,
                                    contentColor = DarkGoldHighlight
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.complete_reset_button),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthTopBar(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text(text = stringResource(R.string.back_text), color = DarkGoldPrimary)
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = DarkGoldTextPrimary)
        Box(modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun AuthCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(30.dp),
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.32f)
            )
            .background(DarkGoldCard, RoundedCornerShape(30.dp))
            .border(
                BorderStroke(1.2.dp, Brush.linearGradient(listOf(DarkGoldPrimary, DarkGoldHighlight))),
                RoundedCornerShape(30.dp)
            )
            .padding(20.dp)
    ) {
        content()
    }
}

private fun isValidEmail(email: String): Boolean {
    return android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
}

private fun isUsername(account: String): Boolean {
    return account.trim().matches(Regex("^[a-zA-Z0-9]+$"))
}

private fun isEmailAccount(account: String): Boolean = account.contains("@")


@Composable
private fun AuthInput(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.22f)
            )
            .background(DarkGoldCard, RoundedCornerShape(20.dp))
            .border(
                BorderStroke(1.dp, DarkGoldPrimary.copy(alpha = 0.7f)),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = label) },
            placeholder = { Text(text = placeholder) },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = DarkGoldTextPrimary,
                unfocusedTextColor = DarkGoldTextPrimary,
                focusedLabelColor = DarkGoldPrimary,
                unfocusedLabelColor = DarkGoldTextSecondary,
                focusedPlaceholderColor = DarkGoldTextSecondary.copy(alpha = 0.72f),
                unfocusedPlaceholderColor = DarkGoldTextSecondary.copy(alpha = 0.72f),
                cursorColor = DarkGoldPrimary
            )
        )
    }
}

@Composable
private fun DarkGoldAccentBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(1.dp)
                .background(Brush.linearGradient(listOf(Color.Transparent, DarkGoldHighlight, Color.Transparent)))
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(width = if (index == 1) 18.dp else 10.dp, height = if (index == 1) 4.dp else 2.dp)
                        .background(DarkGoldPrimary, RoundedCornerShape(99.dp))
                )
            }
        }
    }
}

