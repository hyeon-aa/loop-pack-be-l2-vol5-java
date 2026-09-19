package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductInfo;

public class AdminProductV1Dto {

    public record UpdateStockRequest(int quantity) {}

    public record UpdateRequest(String name, long price) {}

    public record ProductResponse(Long id, Long brandId, String name, long price, int stockQuantity) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(),
                info.brandId(),
                info.name(),
                info.price(),
                info.stockQuantity()
            );
        }
    }
}
