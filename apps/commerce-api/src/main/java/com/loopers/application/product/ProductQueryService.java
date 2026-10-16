package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final BrandService brandService;
    private final ProductService productService;
    private final LikeService likeService;

    @Transactional(readOnly = true)
    public CustomerProductInfo get(Long productId) {
        ProductModel product = productService.get(productId);
        return enrich(List.of(product)).getFirst();
    }

    @Transactional(readOnly = true)
    public ProductPage<CustomerProductInfo> getList(
        Long brandId,
        int page,
        int size,
        ProductSort sort
    ) {
        validatePage(page, size);
        BrandModel brand = brandId == null ? null : brandService.get(brandId);
        if (sort == ProductSort.PRICE_ASC) {
            List<ProductModel> candidates = productService.findActiveByPriceAsc(brandId, page, size);
            return new ProductPage<>(enrich(candidates, brand), page, size);
        }
        List<ProductModel> candidates = brandId == null
            ? productService.findAllActive()
            : productService.findActiveByBrandId(brandId);
        List<CustomerProductInfo> products = enrich(candidates, brand).stream().sorted(comparator(sort)).toList();
        int fromIndex = Math.min(page * size, products.size());
        int toIndex = Math.min(fromIndex + size, products.size());
        var selected = products.subList(fromIndex, toIndex);
        return new ProductPage<>(selected, page, size);
    }

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> getActiveByIdsInOrder(List<Long> productIds) {
        Map<Long, CustomerProductInfo> byId = enrich(productService.findActiveByIds(productIds)).stream()
            .collect(Collectors.toMap(CustomerProductInfo::id, Function.identity()));
        return productIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private List<CustomerProductInfo> enrich(Collection<ProductModel> products) {
        return enrich(products, null);
    }

    /**
     * knownBrand가 있으면 호출자가 이미 조회·검증한 브랜드이므로 다시 조회하지 않는다.
     * 후보 상품은 모두 knownBrand 소속이어야 한다(브랜드 조건으로 조회한 candidates만 넘긴다).
     */
    private List<CustomerProductInfo> enrich(Collection<ProductModel> products, BrandModel knownBrand) {
        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        Map<Long, BrandInfo> brands = knownBrand != null
            ? Map.of(knownBrand.getId(), BrandInfo.from(knownBrand))
            : brandService.getAllActiveByIds(
                    products.stream().map(ProductModel::getBrandId).collect(Collectors.toSet())
                ).stream()
                .map(BrandInfo::from)
                .collect(Collectors.toMap(BrandInfo::id, Function.identity()));
        Map<Long, Long> likeCounts = likeService.countByProductIds(productIds);
        return products.stream()
            .map(product -> new CustomerProductInfo(
                product.getId(),
                brands.get(product.getBrandId()),
                product.getName(),
                product.getPrice(),
                likeCounts.getOrDefault(product.getId(), 0L),
                product.getCreatedAt()
            ))
            .filter(product -> product.brand() != null)
            .toList();
    }

    private static Comparator<CustomerProductInfo> comparator(ProductSort sort) {
        return switch (sort) {
            case LATEST -> Comparator.comparing(CustomerProductInfo::createdAt).reversed()
                .thenComparing(CustomerProductInfo::id, Comparator.reverseOrder());
            case PRICE_ASC -> Comparator.comparingLong(CustomerProductInfo::price)
                .thenComparing(CustomerProductInfo::createdAt, Comparator.reverseOrder())
                .thenComparing(CustomerProductInfo::id, Comparator.reverseOrder());
            case LIKES_DESC -> Comparator.comparingLong(CustomerProductInfo::likeCount).reversed()
                .thenComparing(CustomerProductInfo::createdAt, Comparator.reverseOrder())
                .thenComparing(CustomerProductInfo::id, Comparator.reverseOrder());
        };
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "페이지 번호 또는 크기가 올바르지 않습니다.");
        }
    }
}
