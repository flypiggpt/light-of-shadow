package com.yan.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.yan.client.ui.guide.GuideScreen
import com.yan.client.ui.login.ForgotPasswordScreen
import com.yan.client.ui.login.LoginScreen
import com.yan.client.ui.login.RegisterScreen
import com.yan.client.ui.main.MainScreen
import com.yan.client.settings.AppSettings
import com.yan.client.ui.settings.SettingsScreen
import com.yan.client.ui.shop.ShopScreen
import com.yan.client.ui.theme.ClientTheme

private enum class AuthRoute {
    Login,
    Register,
    ForgotPassword,
    Main,
    Guide,
    Shop,
    Settings
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(applicationContext)
        setContent {
            ClientTheme(darkTheme = false, dynamicColor = false) {
                var route by remember { mutableStateOf(AuthRoute.Login) }

                when (route) {
                    AuthRoute.Login -> {
                        LoginScreen(
                            modifier = Modifier.fillMaxSize(),
                            onLoginClick = { _, _ ->
                                route = AuthRoute.Main
                            },
                            onRegisterClick = {
                                route = AuthRoute.Register
                            },
                            onForgotPasswordClick = {
                                route = AuthRoute.ForgotPassword
                            }
                        )
                    }

                    AuthRoute.Register -> {
                        RegisterScreen(
                            modifier = Modifier.fillMaxSize(),
                            onBack = {
                                route = AuthRoute.Login
                            },
                            onRegisterSuccess = {
                                route = AuthRoute.Login
                            }
                        )
                    }

                    AuthRoute.ForgotPassword -> {
                        ForgotPasswordScreen(
                            modifier = Modifier.fillMaxSize(),
                            onBack = {
                                route = AuthRoute.Login
                            },
                            onResetSuccess = {
                                route = AuthRoute.Login
                            }
                        )
                    }

                    AuthRoute.Main -> {
                        MainScreen(
                            modifier = Modifier.fillMaxSize(),
                            onLogout = {
                                AppSettings.clearSession()
                                route = AuthRoute.Login
                            },
                            onSettingsClick = {
                                route = AuthRoute.Settings
                            },
                            onStartGuide = {
                                route = AuthRoute.Guide
                            },
                            onShopClick = {
                                route = AuthRoute.Shop
                            }
                        )
                    }

                    AuthRoute.Settings -> {
                        SettingsScreen(
                            modifier = Modifier.fillMaxSize(),
                            onBack = {
                                route = AuthRoute.Main
                            },
                            onLogout = {
                                route = AuthRoute.Login
                            }
                        )
                    }

                    AuthRoute.Guide -> {
                        GuideScreen(
                            modifier = Modifier.fillMaxSize(),
                            onBack = {
                                route = AuthRoute.Main
                            },
                            onLogout = {
                                route = AuthRoute.Login
                            }
                        )
                    }

                    AuthRoute.Shop -> {
                        ShopScreen(
                            modifier = Modifier.fillMaxSize(),
                            onBack = {
                                route = AuthRoute.Main
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    ClientTheme(darkTheme = false, dynamicColor = false) {
        LoginScreen()
    }
}