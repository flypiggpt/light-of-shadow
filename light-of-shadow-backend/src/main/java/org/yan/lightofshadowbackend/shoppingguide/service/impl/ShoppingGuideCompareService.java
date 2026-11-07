package org.yan.lightofshadowbackend.shoppingguide.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;

/**
 * 商品对比规则引擎：按关键词抽卖点、生成对比摘要。
 * 纯规则、无状态，独立于会话编排便于维护与测试。
 */
@Service
public class ShoppingGuideCompareService {

    private final ProductMapper productMapper;

    public ShoppingGuideCompareService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /** 对比结果：{summary, items:[{productId,name,highlights,priceCent,stock,coverUrl}]} */
    public Map<String, Object> compare(List<String> productIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<ProductEntity> products = new ArrayList<>();
        for (String productId : productIds) {
            ProductEntity product = requireProduct(productId);
            products.add(product);
            items.add(compareItem(product));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buildCompareSummary(products));
        result.put("items", items);
        return result;
    }

    private ProductEntity requireProduct(String productId) {
        ProductEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    private Map<String, Object> compareItem(ProductEntity product) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productId", product.getId());
        map.put("name", product.getName());
        map.put("highlights", buildHighlights(product));
        map.put("priceCent", product.getPriceCent() == null ? 0 : product.getPriceCent());
        map.put("stock", product.getStock() == null ? 0 : product.getStock());
        map.put("coverUrl", product.getCoverUrl());
        return map;
    }

    private List<String> buildHighlights(ProductEntity product) {
        List<String> highlights = new ArrayList<>();
        String text = ((product.getName() == null ? "" : product.getName()) + " "
                + (product.getDescription() == null ? "" : product.getDescription())).toLowerCase(Locale.ROOT);
        if (text.contains("语音")) highlights.add("语音播报");
        if (text.contains("轻") || text.contains("量")) highlights.add("超轻便设计");
        if (highlights.isEmpty()) highlights.add("专业辅具");
        return highlights;
    }

    private String buildCompareSummary(List<ProductEntity> products) {
        if (products.size() < 2) {
            return "商品数量不足，无法对比。";
        }
        ProductEntity first = products.get(0);
        ProductEntity second = products.get(1);
        return "“" + first.getName() + "”和“" + second.getName() + "”已完成对比，前者更适合优先关注，后者可作为备选。";
    }
}
