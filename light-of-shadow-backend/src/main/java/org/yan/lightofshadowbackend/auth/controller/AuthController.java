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
import org.yan.lightofshadowbackend.auth.dto.ResetPasswordRequest;
import org.yan.lightofshadowbackend.auth.dto.SendCodeRequest;
import org.yan.lightofshadowbackend.auth.dto.SendCodeResponse;
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

    /**
     * 发送验证码。dev 模式下响应 data.verificationCode 会回显，便于联调。
     */
    @PostMapping("/code")
    public ApiResponse<SendCodeResponse> sendCode(@RequestBody SendCodeRequest request) {
        return ApiResponse.success(authService.sendCode(request));
    }

    /** 忘记密码：触发 scene=reset 的发码（与 /code 等价，保留客户端既有路径）。 */
    @PostMapping("/password/forgot")
    public ApiResponse<SendCodeResponse> forgotPassword(@RequestBody SendCodeRequest request) {
        SendCodeRequest req = new SendCodeRequest(
                request.channel() == null ? "email" : request.channel(),
                request.effectiveTarget(),
                null,
                "reset");
        return ApiResponse.success("验证码已发送", authService.sendCode(req));
    }

    /** 重置密码：校验验证码后修改密码。 */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success("密码已重置", null);
    }
}


