package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @Mock
    private BrandService brandService;

    @Mock
    private ProductService productService;

    @Mock
    private LikeService likeService;

    @Mock
    private ProductModel firstProduct;

    @Mock
    private ProductModel secondProduct;

    @Mock
    private BrandModel brand;

    @Test
    void sortsByDerivedLikeCountAndPaginates() {
        when(firstProduct.getId()).thenReturn(1L);
        when(firstProduct.getBrandId()).thenReturn(10L);
        when(firstProduct.getName()).thenReturn("운동화");
        when(firstProduct.getPrice()).thenReturn(100_000L);
        when(secondProduct.getId()).thenReturn(2L);
        when(secondProduct.getBrandId()).thenReturn(10L);
        when(secondProduct.getName()).thenReturn("샌들");
        when(secondProduct.getPrice()).thenReturn(50_000L);
        when(brand.getId()).thenReturn(10L);
        when(brand.getName()).thenReturn("나이키");
        when(productService.findAllActive()).thenReturn(List.of(firstProduct, secondProduct));
        when(brandService.getAllActiveByIds(java.util.Set.of(10L))).thenReturn(List.of(brand));
        when(likeService.countByProductIds(List.of(1L, 2L))).thenReturn(Map.of(1L, 1L, 2L, 3L));

        ProductPage<CustomerProductInfo> result = new ProductQueryService(
            brandService, productService, likeService
        ).getList(null, 0, 1, ProductSort.LIKES_DESC);

        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.items()).extracting(CustomerProductInfo::id).containsExactly(2L);
        assertThat(result.items().getFirst().likeCount()).isEqualTo(3L);
    }
}
