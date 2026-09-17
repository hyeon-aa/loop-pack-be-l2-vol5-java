package com.loopers.application.brand;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandFacadeTest {

    @Mock
    private BrandService brandService;

    @Mock
    private ProductRepository productRepository;

    @Test
    void deletesBrandWhenThereAreNoActiveProducts() {
        when(productRepository.existsActiveByBrandId(1L)).thenReturn(false);

        new BrandFacade(brandService, productRepository).delete(1L);

        verify(brandService).delete(1L);
    }

    @Test
    void rejectsBrandDeleteWhenThereIsAnActiveProduct() {
        when(productRepository.existsActiveByBrandId(1L)).thenReturn(true);

        assertThatThrownBy(() -> new BrandFacade(brandService, productRepository).delete(1L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);

        verify(brandService, never()).delete(1L);
    }
}
