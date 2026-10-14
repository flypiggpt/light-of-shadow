package org.yan.lightofshadowbackend.auth.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.entity.RefreshTokenEntity;

public interface RefreshTokenMapper {

    RefreshTokenEntity selectActiveByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    int insert(RefreshTokenEntity token);
}

