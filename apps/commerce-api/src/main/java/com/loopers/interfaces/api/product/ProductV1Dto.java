package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductPage;

import java.util.List;

public class ProductV1Dto {

    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name());
        }
    }

    public record ProductResponse(Long id, BrandResponse brand, String name, long price, long likeCount) {
        public static ProductResponse from(CustomerProductInfo info) {
            return new ProductResponse(
                info.id(),
                BrandResponse.from(info.brand()),
                info.name(),
                info.price(),
                info.likeCount()
            );
        }
    }

    public record ProductListResponse(List<ProductResponse> items, int page, int size, long totalCount) {
        public static ProductListResponse from(ProductPage<CustomerProductInfo> page) {
            return new ProductListResponse(
                page.items().stream().map(ProductResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalCount()
            );
        }
    }
}
