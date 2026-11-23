package org.yan.lightofshadowbackend.shop.proactive.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactivePrefEntity;

@Mapper
public interface ProactivePrefMapper {

    ProactivePrefEntity selectByUserIdAndType(@Param("userId") String userId, @Param("type") String type);

    int upsert(ProactivePrefEntity pref);
}
