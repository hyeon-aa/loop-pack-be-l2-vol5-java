package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.brand.BrandV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class AdminBrandV1Controller implements AdminBrandV1ApiSpec {

    private final BrandFacade brandFacade;

    @GetMapping
    public ApiResponse<List<BrandV1Dto.BrandResponse>> getBrands() {
        return ApiResponse.success(brandFacade.getAll().stream().map(BrandV1Dto.BrandResponse::from).toList());
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> getBrand(@PathVariable Long brandId) {
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brandFacade.get(brandId)));
    }

    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> createBrand(
        @RequestBody BrandV1Dto.CreateRequest request
    ) {
        BrandInfo info = brandFacade.create(request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(BrandV1Dto.BrandResponse.from(info)));
    }

    @PutMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> updateBrand(
        @PathVariable Long brandId,
        @RequestBody BrandV1Dto.CreateRequest request
    ) {
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(
            brandFacade.update(brandId, request.name(), request.description())
        ));
    }

    @DeleteMapping("/{brandId}")
    public ResponseEntity<Void> deleteBrand(@PathVariable Long brandId) {
        brandFacade.delete(brandId);
        return ResponseEntity.noContent().build();
    }
}
