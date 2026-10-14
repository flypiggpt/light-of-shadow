package org.yan.lightofshadowbackend.shop.service;

import org.springframework.stereotype.Component;
import org.yan.lightofshadowbackend.auth.service.TokenService;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.entity.UserEntity;
import org.yan.lightofshadowbackend.entity.UserStatus;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.auth.mapper.UserMapper;

@Component
public class ShopAuthSupport {

    private final TokenService tokenService;
    private final UserMapper userMapper;

    public ShopAuthSupport(TokenService tokenService, UserMapper userMapper) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    public String resolveUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "未授权");
        }
        String token = authHeader.substring(7).trim();
        String userId;
        try {
            userId = tokenService.parseUserIdFromAccessToken(token);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "未授权");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        return userId;
    }

    public String resolveMerchantId(String authHeader) {
        String userId = resolveUserId(authHeader);
        UserEntity user = userMapper.selectById(userId);
        // require role MERCHANT
        if (user.getRole() == null || !user.getRole().equals("MERCHANT")) {
            throw new ApiException(ErrorCode.FORBIDDEN, "需要商家角色");
        }
        return userId;
    }
}

