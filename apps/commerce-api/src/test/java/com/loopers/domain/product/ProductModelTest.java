package com.loopers.domain.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProductModelTest {

    @Test
    void createsProductWithNormalizedNameAndInitialStock() {
        ProductModel product = new ProductModel(1L, "  운동화  ", 100_000L, 0);

        assertThat(product.getBrandId()).isEqualTo(1L);
        assertThat(product.getName()).isEqualTo("운동화");
        assertThat(product.getPrice()).isEqualTo(100_000L);
        assertThat(product.getStockQuantity()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsBlankProductName(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductModel(1L, name, 1L, 0));
    }

    @Test
    void rejectsProductNameLongerThan100CodePoints() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProductModel(1L, "😀".repeat(101), 1L, 0));
    }

    @Test
    void rejectsInvalidBrandPriceAndInitialStock() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductModel(null, "상품", 1L, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductModel(0L, "상품", 1L, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductModel(1L, "상품", 0L, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductModel(1L, "상품", 1L, -1));
    }

    @Test
    void delegatesStockDecreaseToInternalStock() {
        ProductModel product = new ProductModel(1L, "상품", 1L, 5);

        product.decreaseStock(2);

        assertThat(product.getStockQuantity()).isEqualTo(3);
    }

    @Test
    void preservesStockWhenInternalStockRejectsDecrease() {
        ProductModel product = new ProductModel(1L, "상품", 1L, 5);

        assertThatIllegalArgumentException().isThrownBy(() -> product.decreaseStock(6));

        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void replacesInternalStockWithRequestedFinalQuantity() {
        ProductModel product = new ProductModel(1L, "상품", 1L, 5);

        product.changeStock(0);

        assertThat(product.getStockQuantity()).isZero();
    }

    @Test
    void updatesNameAndPriceWhileKeepingBrandAndStock() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 5);

        product.update("  러닝화  ", 120_000L);

        assertThat(product.getBrandId()).isEqualTo(1L);
        assertThat(product.getName()).isEqualTo("러닝화");
        assertThat(product.getPrice()).isEqualTo(120_000L);
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void preservesProductStateWhenUpdateIsInvalid() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 5);

        assertThatIllegalArgumentException().isThrownBy(() -> product.update("러닝화", 0L));

        assertThat(product.getName()).isEqualTo("운동화");
        assertThat(product.getPrice()).isEqualTo(100_000L);
    }

    @Test
    void marksProductDeletedWithoutChangingStock() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 5);

        product.markDeleted();

        assertThat(product.getDeletedAt()).isNotNull();
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }
}
