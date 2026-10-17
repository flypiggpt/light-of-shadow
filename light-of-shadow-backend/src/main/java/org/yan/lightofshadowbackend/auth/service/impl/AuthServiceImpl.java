package org.yan.lightofshadowbackend.auth.service.impl;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yan.lightofshadowbackend.auth.dto.LoginRequest;
import org.yan.lightofshadowbackend.auth.dto.LoginResponse;
import org.yan.lightofshadowbackend.auth.dto.RefreshRequest;
import org.yan.lightofshadowbackend.auth.dto.RefreshResponse;
import org.yan.lightofshadowbackend.auth.dto.RegisterRequest;
import org.yan.lightofshadowbackend.auth.dto.RegisterResponse;
import org.yan.lightofshadowbackend.auth.dto.ResetPasswordRequest;
import org.yan.lightofshadowbackend.auth.dto.SendCodeRequest;
import org.yan.lightofshadowbackend.auth.dto.SendCodeResponse;
import org.yan.lightofshadowbackend.auth.code.VerificationCodeService;
import org.springframework.core.env.Environment;
import org.yan.lightofshadowbackend.auth.mapper.RefreshTokenMapper;
import org.yan.lightofshadowbackend.auth.mapper.UserMapper;
import org.yan.lightofshadowbackend.auth.service.AuthService;
import org.yan.lightofshadowbackend.auth.service.TokenService;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.config.AuthProperties;
import org.yan.lightofshadowbackend.entity.RefreshTokenEntity;
import org.yan.lightofshadowbackend.entity.UserEntity;
import org.yan.lightofshadowbackend.entity.UserStatus;
import org.yan.lightofshadowbackend.exception.ApiException;

@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthProperties authProperties;
    private final VerificationCodeService verificationCodeService;
    private final Environment environment;

    public AuthServiceImpl(UserMapper userMapper,
                           RefreshTokenMapper refreshTokenMapper,
                           PasswordEncoder passwordEncoder,
                           TokenService tokenService,
                           AuthProperties authProperties,
                           VerificationCodeService verificationCodeService,
                           Environment environment) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.authProperties = authProperties;
        this.verificationCodeService = verificationCodeService;
        this.environment = environment;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String account = normalizeAccount(requireText(request.account(), 128, "账号不能为空", "账号长度不能超过128"));
        boolean hasPassword = request.password() != null && !request.password().isBlank();
        boolean hasCode = request.verificationCode() != null && !request.verificationCode().isBlank();
        if (!hasPassword && !hasCode) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "请输入密码或验证码");
        }
        if (hasPassword && hasCode) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "密码与验证码只能二选一");
        }

        UserEntity user = userMapper.selectByAccount(account);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        if (hasPassword) {
            String rawPassword = requireText(request.password(), 72, "密码不能为空", "密码长度不能超过72");
            if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
            }
        } else {
            // 验证码登录：校验并消费
            verificationCodeService.verifyAndConsume(account, VerificationCodeService.Scene.LOGIN.value(), request.verificationCode().trim());
        }

        String accessToken = tokenService.createAccessToken(user, authProperties.accessTokenTtl());
        String refreshToken = tokenService.generateRefreshToken();
        persistRefreshToken(user.getId(), refreshToken);

        return new LoginResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
                accessToken,
                refreshToken,
                authProperties.accessTokenTtl().toSeconds());
    }

    @Override
    public RegisterResponse register(RegisterRequest request) {
        String account = normalizeAccount(requireText(request.account(), 128, "账号不能为空", "账号长度不能超过128"));
        String password = requireText(request.password(), 72, "密码不能为空", "密码长度不能超过72");
        String nickname = requireText(request.nickname(), 64, "昵称不能为空", "昵称长度不能超过64");
        if (password.length() < 8) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "密码长度不能少于8位");
        }
        // 注册必须先验码（scene=register）
        verificationCodeService.verifyAndConsume(account, VerificationCodeService.Scene.REGISTER.value(), request.verificationCode());
        if (userMapper.selectByAccount(account) != null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "账号已存在");
        }

        UserEntity user = new UserEntity();
        user.setId(generateUserId());
        user.setAccount(account);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname.trim());
        user.setAvatarUrl("");
        user.setStatus(UserStatus.ACTIVE);
        user.setRole("USER");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        int inserted = userMapper.insert(user);
        if (inserted != 1) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "注册失败");
        }
        return new RegisterResponse(user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public RefreshResponse refresh(RefreshRequest request) {
        String tokenHash = tokenService.hashRefreshToken(request.refreshToken());
        RefreshTokenEntity activeToken = refreshTokenMapper.selectActiveByTokenHash(tokenHash, Instant.now());
        if (activeToken == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "refreshToken无效或已过期");
        }

        UserEntity user = userMapper.selectById(activeToken.getUserId());
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        String accessToken = tokenService.createAccessToken(user, authProperties.accessTokenTtl());
        return new RefreshResponse(accessToken, authProperties.accessTokenTtl().toSeconds());
    }

    @Override
    public SendCodeResponse sendCode(SendCodeRequest request) {
        // channel 预留 sms；当前仅 email
        String channel = request.channel() == null ? "email" : request.channel().trim();
        if (!"email".equalsIgnoreCase(channel)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "channel当前仅支持email");
        }
        VerificationCodeService.SendResult result = verificationCodeService.send(request.effectiveTarget(), request.scene());
        return buildSendResponse(result);
    }

    private SendCodeResponse buildSendResponse(VerificationCodeService.SendResult result) {
        boolean dev = isDevProfile();
        return new SendCodeResponse(
                result.expiresIn(),
                result.resendAfter(),
                dev ? result.code() : null
        );
    }

    private boolean isDevProfile() {
        if (environment == null) {
            return true;
        }
        // prod 下不回显；其余（default/dev）回显便于联调
        return !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
    }

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        String account = normalizeAccount(requireText(request.account(), 128, "账号不能为空", "账号长度不能超过128"));
        String newPassword = requireText(request.newPassword(), 72, "新密码不能为空", "新密码长度不能超过72");
        if (newPassword.length() < 8) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "新密码长度不能少于8位");
        }
        verificationCodeService.verifyAndConsume(account, VerificationCodeService.Scene.RESET.value(), request.verificationCode());

        UserEntity user = userMapper.selectByAccount(account);
        if (user == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());
        if (userMapper.update(user) != 1) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "密码重置失败");
        }
    }

    private void persistRefreshToken(String userId, String refreshToken) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setUserId(userId);
        token.setTokenHash(tokenService.hashRefreshToken(refreshToken));
        token.setExpiresAt(Instant.now().plus(authProperties.refreshTokenTtl()));
        token.setRevoked(false);
        token.setCreatedAt(Instant.now());
        token.setUpdatedAt(Instant.now());

        int inserted = refreshTokenMapper.insert(token);
        if (inserted != 1) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "刷新令牌保存失败");
        }
    }

    private String normalizeAccount(String account) {
        return account == null ? null : account.trim().toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, int maxLength, String blankMessage, String tooLongMessage) {
        if (value == null || value.trim().isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, blankMessage);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new ApiException(ErrorCode.BAD_REQUEST, tooLongMessage);
        }
        return trimmed;
    }

    private String generateUserId() {
        return "u_" + UUID.randomUUID().toString().replace("-", "");
    }
}


