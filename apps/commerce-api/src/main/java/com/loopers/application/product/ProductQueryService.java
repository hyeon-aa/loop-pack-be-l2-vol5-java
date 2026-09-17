package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
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
        if (brandId != null) {
            brandService.get(brandId);
        }
        List<CustomerProductInfo> products = enrich(productService.findAllActive()).stream()
            .filter(product -> brandId == null || product.brand().id().equals(brandId))
            .sorted(comparator(sort))
            .toList();
        int fromIndex = Math.min(page * size, products.size());
        int toIndex = Math.min(fromIndex + size, products.size());
        return new ProductPage<>(products.subList(fromIndex, toIndex), page, size, products.size());
    }

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> getActiveByIdsInOrder(List<Long> productIds) {
        Map<Long, CustomerProductInfo> byId = enrich(productService.findActiveByIds(productIds)).stream()
            .collect(Collectors.toMap(CustomerProductInfo::id, Function.identity()));
        return productIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private List<CustomerProductInfo> enrich(Collection<ProductModel> products) {
        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        Map<Long, BrandInfo> brands = brandService.getAllActiveByIds(
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
