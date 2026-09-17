package com.loopers.domain.brand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BrandModelTest {

    @Test
    void preservesCaseAndInternalSpacesWhileStrippingSurroundingWhitespace() {
        BrandModel brand = new BrandModel(" \tNike Air\n", "  브랜드 설명  ");

        assertThat(brand.getName()).isEqualTo("Nike Air");
        assertThat(brand.getDescription()).isEqualTo("  브랜드 설명  ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingOrBlankName(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new BrandModel(name, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsNameAtLengthBoundaries(int length) {
        String name = "가".repeat(length);

        assertThat(new BrandModel(" " + name + " ", null).getName()).isEqualTo(name);
    }

    @Test
    void rejectsNameLongerThan100CodePoints() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BrandModel("가".repeat(101), null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void normalizesAbsentDescriptionToNull(String description) {
        assertThat(new BrandModel("브랜드", description).getDescription()).isNull();
    }

    @Test
    void acceptsDescriptionOf1000CodePoints() {
        String description = "가".repeat(1000);

        assertThat(new BrandModel("브랜드", description).getDescription()).isEqualTo(description);
    }

    @Test
    void rejectsDescriptionLongerThan1000CodePoints() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new BrandModel("브랜드", "가".repeat(1001)));
    }

    @Test
    void countsSupplementaryCharactersAsSingleCodePoints() {
        String name = "😀".repeat(100);
        String description = "😀".repeat(1000);

        BrandModel brand = new BrandModel(name, description);

        assertThat(brand.getName()).isEqualTo(name);
        assertThat(brand.getDescription()).isEqualTo(description);
        assertThatIllegalArgumentException().isThrownBy(() -> new BrandModel(name + "😀", null));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new BrandModel("브랜드", description + "😀"));
    }

    @Test
    void marksBrandDeleted() {
        BrandModel brand = new BrandModel("브랜드", null);

        brand.markDeleted();

        assertThat(brand.getDeletedAt()).isNotNull();
    }
}
