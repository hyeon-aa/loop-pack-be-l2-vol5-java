package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;

import java.time.ZonedDateTime;

public record CustomerProductInfo(
    Long id,
    BrandInfo brand,
    String name,
    long price,
    long likeCount,
    ZonedDateTime createdAt
) {}
