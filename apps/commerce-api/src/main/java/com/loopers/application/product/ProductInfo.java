package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

public record ProductInfo(Long id, Long brandId, String name, long price, int stockQuantity) {
    public static ProductInfo from(ProductModel model) {
        return new ProductInfo(
            model.getId(),
            model.getBrandId(),
            model.getName(),
            model.getPrice(),
            model.getStockQuantity()
        );
    }
}
