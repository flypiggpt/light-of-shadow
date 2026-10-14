package org.yan.lightofshadowbackend.shop.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;

import java.util.List;

@Mapper
public interface ProductMapper {
    void insert(ProductEntity product);

    ProductEntity selectById(@Param("id") String id);

    int decreaseStockIfEnough(@Param("id") String id, @Param("quantity") int quantity);

    ProductEntity selectByIdAndMerchantId(@Param("id") String id, @Param("merchantId") String merchantId);

    List<ProductEntity> selectPage(@Param("offset") int offset, @Param("limit") int limit, @Param("keyword") String keyword);

    long count(@Param("keyword") String keyword);

    List<ProductEntity> selectPageByMerchantId(@Param("merchantId") String merchantId,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("keyword") String keyword);

    long countByMerchantId(@Param("merchantId") String merchantId, @Param("keyword") String keyword);

    int updateByIdAndMerchantId(ProductEntity product);

    int deleteByIdAndMerchantId(@Param("id") String id, @Param("merchantId") String merchantId);
}

