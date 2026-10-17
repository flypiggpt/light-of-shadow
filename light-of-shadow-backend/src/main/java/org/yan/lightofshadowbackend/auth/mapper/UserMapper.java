package org.yan.lightofshadowbackend.auth.mapper;

import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.entity.UserEntity;

public interface UserMapper {

    UserEntity selectByAccount(@Param("account") String account);

    UserEntity selectById(@Param("id") String id);

    int insert(UserEntity user);

    int update(UserEntity user);
}

