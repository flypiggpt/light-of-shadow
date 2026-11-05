package com.yan.client.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yan.client.R
import com.yan.client.ui.theme.NeumorphismSurfacePressed
import com.yan.client.network.ApiClient
import com.yan.client.network.TokenStore
import com.yan.client.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Dark gold style overrides for this screen (kept local so functionality isn't changed)
private val DarkBg = Color(0xFF0B0A09)
private val GoldPrimary = Color(0xFFC89B3B)
private val GoldHighlight = Color(0xFFF0C869)
private val DarkCard = Color(0xFF191414)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondaryGold = Color(0xFFD9C79A)

@Composable
fun LoginScreen(
	modifier: Modifier = Modifier,
	onLoginClick: (String, String) -> Unit = { _, _ -> },
	onRegisterClick: () -> Unit = {},
	onForgotPasswordClick: () -> Unit = {}
) {
	val snackbarHostState = remember { SnackbarHostState() }
	val focusManager = LocalFocusManager.current
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val emptyFieldsMessage = stringResource(R.string.empty_fields_hint)

	var account by rememberSaveable { mutableStateOf("") }
	var password by rememberSaveable { mutableStateOf("") }
	var verificationCode by rememberSaveable { mutableStateOf("") }
	var loginByCode by rememberSaveable { mutableStateOf(false) }
	var isLoading by remember { mutableStateOf(false) }

	Scaffold(
		modifier = modifier,
		snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
		containerColor = DarkBg
	) { innerPadding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					brush = Brush.verticalGradient(
						colors = listOf(DarkBg, DarkBg.copy(alpha = 0.95f))
					)
				)
				.padding(innerPadding)
				.padding(horizontal = 24.dp, vertical = 16.dp),
			contentAlignment = Alignment.Center
		) {
			Column(
				modifier = Modifier.fillMaxWidth(),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(20.dp)
			) {
				BrandHeader()

				NeumorphicSurface(
					modifier = Modifier.fillMaxWidth(),
					shape = RoundedCornerShape(28.dp),
					contentPadding = PaddingValues(24.dp)
				) {
					Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(
								text = stringResource(R.string.login_card_title),
								style = MaterialTheme.typography.headlineMedium,
								color = TextPrimary
							)
							Text(
								text = stringResource(R.string.login_card_desc),
								style = MaterialTheme.typography.bodyMedium,
								color = TextSecondaryGold
							)
						}

						NeumorphicTextField(
							label = stringResource(R.string.email_label),
							placeholder = stringResource(R.string.email_placeholder),
							value = account,
							onValueChange = { account = it },
							keyboardOptions = KeyboardOptions(
								keyboardType = KeyboardType.Email,
								imeAction = ImeAction.Next
							),
							keyboardActions = KeyboardActions(
								onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }
							)
						)

						if (!loginByCode) {
							NeumorphicTextField(
								label = stringResource(R.string.password_label),
								placeholder = stringResource(R.string.password_placeholder),
								value = password,
								onValueChange = { password = it },
								isPassword = true,
								keyboardOptions = KeyboardOptions(
									keyboardType = KeyboardType.Password,
									imeAction = ImeAction.Done
								),
								keyboardActions = KeyboardActions(
									onDone = {
										focusManager.clearFocus()
										submitLogin(
											account = account,
											password = password,
											onLoginClick = onLoginClick,
											snackbarHostState = snackbarHostState,
											scope = scope,
											emptyFieldsMessage = emptyFieldsMessage,
											onLoadingChange = { isLoading = it }
										)
									}
								)
							)
						} else {
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(8.dp)
							) {
								NeumorphicTextField(
									modifier = Modifier.weight(1f),
									label = stringResource(R.string.verification_code_label),
									placeholder = stringResource(R.string.verification_code_placeholder),
									value = verificationCode,
									onValueChange = { verificationCode = it },
									keyboardOptions = KeyboardOptions(
										keyboardType = KeyboardType.Number,
										imeAction = ImeAction.Done
									),
									keyboardActions = KeyboardActions(
										onDone = {
											focusManager.clearFocus()
											submitLogin(
												account = account,
												password = "",
												verificationCode = verificationCode,
												loginByCode = true,
												onLoginClick = onLoginClick,
												snackbarHostState = snackbarHostState,
												scope = scope,
												emptyFieldsMessage = emptyFieldsMessage,
												onLoadingChange = { isLoading = it }
											)
										}
									)
								)
								Button(
									onClick = {
										when {
											account.isBlank() -> {
												scope.launch {
													snackbarHostState.showSnackbar(context.getString(R.string.code_login_need_email_hint))
												}
												return@Button
											}
											!isEmailAccount(account) -> {
												scope.launch {
													snackbarHostState.showSnackbar(context.getString(R.string.email_only_code_hint))
												}
												return@Button
											}
											!isValidEmail(account) -> {
												scope.launch {
													snackbarHostState.showSnackbar(context.getString(R.string.email_invalid_hint))
												}
												return@Button
											}
										}
										scope.launch {
											try {
												val resp = withContext(Dispatchers.IO) {
													ApiClient.sendCode(account.trim(), "login")
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
									modifier = Modifier
										.padding(top = 22.dp)
										.height(52.dp),
									shape = RoundedCornerShape(16.dp),
									colors = ButtonDefaults.buttonColors(
										containerColor = GoldPrimary.copy(alpha = 0.9f),
										contentColor = DarkBg
									)
								) {
									Text(stringResource(R.string.send_code_button), style = MaterialTheme.typography.labelLarge)
								}
							}
						}

						TextButton(
							onClick = {
								if (!loginByCode && account.isNotBlank() && !isEmailAccount(account)) {
									scope.launch {
										snackbarHostState.showSnackbar(context.getString(R.string.email_only_code_hint))
									}
								} else {
									loginByCode = !loginByCode
								}
							},
							contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
							modifier = Modifier.fillMaxWidth()
						) {
							Text(
								text = stringResource(if (loginByCode) R.string.login_by_password else R.string.login_by_code),
								color = GoldPrimary,
								style = MaterialTheme.typography.labelLarge
							)
						}

						Button(
							onClick = {
								focusManager.clearFocus()
								submitLogin(
									account = account,
									password = password,
									verificationCode = verificationCode,
									loginByCode = loginByCode,
									onLoginClick = onLoginClick,
									snackbarHostState = snackbarHostState,
									scope = scope,
									emptyFieldsMessage = emptyFieldsMessage,
									onLoadingChange = { isLoading = it }
								)
							},
							enabled = !isLoading,
							modifier = Modifier
								.fillMaxWidth()
								.height(56.dp)
								.border(BorderStroke(1.8.dp, Brush.linearGradient(listOf(GoldPrimary, GoldHighlight))), RoundedCornerShape(18.dp)),
							shape = RoundedCornerShape(18.dp),
							colors = ButtonDefaults.buttonColors(
								containerColor = DarkCard,
								contentColor = GoldHighlight,
								disabledContainerColor = NeumorphismSurfacePressed,
								disabledContentColor = TextSecondaryGold
							),
							elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
						) {
							Text(
								text = stringResource(R.string.login_button),
								style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
							)
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							TextButton(
								onClick = {
									onRegisterClick()
								},
								contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
							) {
								Text(
									text = stringResource(R.string.register_text),
									color = GoldPrimary,
									style = MaterialTheme.typography.labelLarge
								)
							}

							TextButton(
								onClick = {
									onForgotPasswordClick()
								},
								contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
							) {
								Text(
									text = stringResource(R.string.forgot_password_text),
									color = TextSecondaryGold,
									style = MaterialTheme.typography.labelLarge
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
private fun BrandHeader() {
	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp)
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 28.dp)
				.height(54.dp),
			contentAlignment = Alignment.Center
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth(0.72f)
					.height(1.dp)
					.background(Brush.linearGradient(listOf(Color.Transparent, GoldHighlight, Color.Transparent)))
					.shadow(6.dp)
			)
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
				repeat(3) { index ->
					Box(
						modifier = Modifier
							.size(width = if (index == 1) 18.dp else 10.dp, height = if (index == 1) 4.dp else 2.dp)
							.background(GoldPrimary, RoundedCornerShape(99.dp))
					)
				}
			}
		}

		Text(
			text = stringResource(R.string.login_title),
			style = MaterialTheme.typography.headlineLarge,
			color = TextPrimary
		)

		Text(
			text = stringResource(R.string.login_subtitle),
			style = MaterialTheme.typography.bodyMedium,
			color = TextSecondaryGold,
			textAlign = TextAlign.Center
		)
	}
}

@Composable
private fun NeumorphicSurface(
	modifier: Modifier = Modifier,
	shape: RoundedCornerShape,
	contentPadding: PaddingValues,
	content: @Composable BoxScope.() -> Unit
) {
	Box(
		modifier = modifier
			.shadow(
				elevation = 10.dp,
				shape = shape,
				ambientColor = Color.Black.copy(alpha = 0.32f),
				spotColor = Color.Black.copy(alpha = 0.42f)
			)
			.background(DarkCard, shape)
			.border(BorderStroke(1.2.dp, Brush.linearGradient(listOf(GoldPrimary.copy(alpha = 0.9f), GoldHighlight.copy(alpha = 0.9f)))), shape)
			.padding(contentPadding)
	) {
		content()
	}
}

@Composable
private fun NeumorphicTextField(
	label: String,
	placeholder: String,
	value: String,
	onValueChange: (String) -> Unit,
	keyboardOptions: KeyboardOptions,
	keyboardActions: KeyboardActions,
	isPassword: Boolean = false,
	modifier: Modifier = Modifier
) {
	NeumorphicSurface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(22.dp),
		contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
	) {
		TextField(
			value = value,
			onValueChange = onValueChange,
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
			label = { Text(text = label, color = TextSecondaryGold) },
			placeholder = { Text(text = placeholder, color = TextSecondaryGold.copy(alpha = 0.75f)) },
			visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
			keyboardOptions = keyboardOptions,
			keyboardActions = keyboardActions,
			colors = TextFieldDefaults.colors(
				focusedContainerColor = Color.Transparent,
				unfocusedContainerColor = Color.Transparent,
				disabledContainerColor = Color.Transparent,
				focusedTextColor = TextPrimary,
				unfocusedTextColor = TextPrimary,
				disabledTextColor = TextSecondaryGold,
				cursorColor = GoldPrimary,
				focusedIndicatorColor = Color.Transparent,
				unfocusedIndicatorColor = Color.Transparent,
				disabledIndicatorColor = Color.Transparent,
				focusedLabelColor = GoldPrimary,
				unfocusedLabelColor = TextSecondaryGold,
				focusedPlaceholderColor = TextSecondaryGold.copy(alpha = 0.7f),
				unfocusedPlaceholderColor = TextSecondaryGold.copy(alpha = 0.7f)
			),
			textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
			shape = RoundedCornerShape(22.dp)
		)
	}
}

private fun isValidEmail(email: String): Boolean {
	return android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
}

private fun isEmailAccount(account: String): Boolean = account.contains("@")
private fun isUsername(account: String): Boolean = account.trim().matches(Regex("^[a-zA-Z0-9]+$"))


private fun submitLogin(
	account: String,
	password: String,
	verificationCode: String = "",
	loginByCode: Boolean = false,
	onLoginClick: (String, String) -> Unit,
	snackbarHostState: SnackbarHostState,
	scope: kotlinx.coroutines.CoroutineScope,
	emptyFieldsMessage: String,
	onLoadingChange: (Boolean) -> Unit
) {
	if (account.isBlank() || (!loginByCode && password.isBlank()) || (loginByCode && verificationCode.isBlank())) {
		scope.launch {
			snackbarHostState.showSnackbar(emptyFieldsMessage)
		}
		return
	}
	if (loginByCode && !isEmailAccount(account)) {
		scope.launch {
			snackbarHostState.showSnackbar("验证码仅支持邮箱账号，请使用邮箱")
		}
		return
	}
	if (!loginByCode && !isEmailAccount(account) && !isUsername(account)) {
		scope.launch {
			snackbarHostState.showSnackbar("用户名只能包含字母或数字")
		}
		return
	}

	// Perform network login off the main thread and handle response
	scope.launch {
		onLoadingChange(true)
		try {
			// Use withTimeoutOrNull to enforce a 15-second timeout
			val resp = withTimeoutOrNull(15_000L) {
				withContext(Dispatchers.IO) {
					if (loginByCode) {
						ApiClient.loginWithCode(account.trim(), verificationCode.trim())
					} else {
						ApiClient.login(account.trim(), password)
					}
				}
			}
			
			if (resp == null) {
				// Timeout occurred
				scope.launch {
					snackbarHostState.showSnackbar("网络超时：请求无响应，请检查网络连接")
				}
			} else {
				if (resp.code == 0) {
					// store tokens (in-memory for now)
					TokenStore.accessToken = resp.data?.let { if (it.has("accessToken") && !it.isNull("accessToken")) it.optString("accessToken") else null }
					TokenStore.refreshToken = resp.data?.let { if (it.has("refreshToken") && !it.isNull("refreshToken")) it.optString("refreshToken") else null }
					TokenStore.expiresIn = resp.data?.optLong("expiresIn", 0L) ?: 0L
					AppSettings.saveLoginProfile(
						account = account.trim(),
						nickname = resp.data?.optString("nickname")?.takeIf { it.isNotBlank() }
					)

					// Ensure onLoginClick runs on main thread to propagate state changes properly
					withContext(Dispatchers.Main) {
						onLoginClick(account.trim(), password)
					}
				} else {
					val msg = resp.message ?: "登录失败 (code=${resp.code})"
					scope.launch {
						snackbarHostState.showSnackbar(msg)
					}
				}
			}
		} catch (e: Exception) {
			scope.launch {
				snackbarHostState.showSnackbar("网络错误：${e.message ?: "未知错误"}")
			}
		} finally {
			onLoadingChange(false)
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
	LoginScreen()
}


