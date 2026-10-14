package org.yan.lightofshadowbackend.auth.service;

import org.yan.lightofshadowbackend.auth.dto.LoginRequest;
import org.yan.lightofshadowbackend.auth.dto.LoginResponse;
import org.yan.lightofshadowbackend.auth.dto.RefreshRequest;
import org.yan.lightofshadowbackend.auth.dto.RefreshResponse;
import org.yan.lightofshadowbackend.auth.dto.RegisterRequest;
import org.yan.lightofshadowbackend.auth.dto.RegisterResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    RegisterResponse register(RegisterRequest request);

    RefreshResponse refresh(RefreshRequest request);
}

