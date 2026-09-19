package com.loopers.interfaces.api.admin.brand;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.brand.BrandV1Dto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Admin Brand V1 API", description = "관리자 브랜드 관리 API입니다.")
public interface AdminBrandV1ApiSpec {

    @Operation(summary = "브랜드 등록", description = "새 브랜드를 등록합니다.")
    ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> createBrand(BrandV1Dto.CreateRequest request);
}
