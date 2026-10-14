package org.yan.lightofshadowbackend.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.auth.dto.LoginRequest;
import org.yan.lightofshadowbackend.auth.dto.LoginResponse;
import org.yan.lightofshadowbackend.auth.dto.RefreshRequest;
import org.yan.lightofshadowbackend.auth.dto.RefreshResponse;
import org.yan.lightofshadowbackend.auth.dto.RegisterRequest;
import org.yan.lightofshadowbackend.auth.dto.RegisterResponse;
import org.yan.lightofshadowbackend.auth.service.AuthService;
import org.yan.lightofshadowbackend.common.ApiResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@RequestBody RegisterRequest request) {
        return ApiResponse.success("注册成功", authService.register(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }
}


