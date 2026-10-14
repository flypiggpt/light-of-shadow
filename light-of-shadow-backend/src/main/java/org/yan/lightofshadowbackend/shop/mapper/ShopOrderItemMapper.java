package org.yan.lightofshadowbackend.shop.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderItemEntity;

@Mapper
public interface ShopOrderItemMapper {

    int insert(ShopOrderItemEntity orderItem);
}

