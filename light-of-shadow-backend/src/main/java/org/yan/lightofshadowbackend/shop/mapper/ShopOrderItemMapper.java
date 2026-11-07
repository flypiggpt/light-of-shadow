package org.yan.lightofshadowbackend.shop.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderItemEntity;

@Mapper
public interface ShopOrderItemMapper {

    int insert(ShopOrderItemEntity orderItem);

    List<ShopOrderItemEntity> selectByOrderId(@Param("orderId") String orderId);

    int deleteByOrderId(@Param("orderId") String orderId);
}

