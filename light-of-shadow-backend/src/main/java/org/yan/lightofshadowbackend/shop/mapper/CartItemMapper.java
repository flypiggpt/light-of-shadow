package org.yan.lightofshadowbackend.shop.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.entity.CartItemEntity;

@Mapper
public interface CartItemMapper {

    List<CartItemEntity> selectByUserId(@Param("userId") String userId);

    CartItemEntity selectById(@Param("id") Long id);

    CartItemEntity selectByIdAndUserId(@Param("id") Long id, @Param("userId") String userId);

    CartItemEntity selectByUserIdAndProductId(@Param("userId") String userId, @Param("productId") String productId);

    int insert(CartItemEntity item);

    int updateQuantityByIdAndUserId(@Param("id") Long id, @Param("userId") String userId, @Param("quantity") int quantity);

    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") String userId);

    int deleteByUserId(@Param("userId") String userId);
}

