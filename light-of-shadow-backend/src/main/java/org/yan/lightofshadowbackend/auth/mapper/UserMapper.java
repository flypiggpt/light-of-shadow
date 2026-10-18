package org.yan.lightofshadowbackend.auth.mapper;

import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.entity.UserEntity;

public interface UserMapper {

    UserEntity selectByUsername(@Param("username") String username);

    UserEntity selectByEmail(@Param("email") String email);

    UserEntity selectById(@Param("id") String id);

    int insert(UserEntity user);

    int update(UserEntity user);
}
