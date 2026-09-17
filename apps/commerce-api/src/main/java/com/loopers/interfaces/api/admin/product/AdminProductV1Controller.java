package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductV1Controller {

    private final ProductFacade productFacade;

    @GetMapping
    public ApiResponse<List<AdminProductV1Dto.ProductResponse>> getProducts() {
        return ApiResponse.success(
            productFacade.getAll().stream().map(AdminProductV1Dto.ProductResponse::from).toList()
        );
    }

    @GetMapping("/{productId}")
    public ApiResponse<AdminProductV1Dto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(productFacade.get(productId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminProductV1Dto.ProductResponse>> createProduct(
        @RequestBody CreateRequest request
    ) {
        ProductInfo info = productFacade.create(
            request.brandId(), request.name(), request.price(), request.initialStockQuantity()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(AdminProductV1Dto.ProductResponse.from(info)));
    }

    public record CreateRequest(Long brandId, String name, long price, int initialStockQuantity) {}

    @PutMapping("/{productId}")
    public ApiResponse<AdminProductV1Dto.ProductResponse> updateProduct(
        @PathVariable Long productId,
        @RequestBody AdminProductV1Dto.UpdateRequest request
    ) {
        ProductInfo info = productFacade.update(productId, request.name(), request.price());
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        productFacade.delete(productId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{productId}/stock")
    public ApiResponse<AdminProductV1Dto.ProductResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody AdminProductV1Dto.UpdateStockRequest request
    ) {
        ProductInfo info = productFacade.changeStock(productId, request.quantity());
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(info));
    }
}
