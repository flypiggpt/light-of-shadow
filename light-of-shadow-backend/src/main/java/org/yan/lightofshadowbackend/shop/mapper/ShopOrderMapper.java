package org.yan.lightofshadowbackend.shop.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderEntity;

@Mapper
public interface ShopOrderMapper {

    int insert(ShopOrderEntity order);

    ShopOrderEntity selectById(@Param("id") String id);

    List<ShopOrderEntity> selectPageByUserId(@Param("userId") String userId,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("status") String status);

    long countByUserId(@Param("userId") String userId, @Param("status") String status);
}

