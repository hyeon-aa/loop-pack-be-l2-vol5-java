package com.loopers.domain.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StockTest {

    @Test
    void decreasesQuantityByRequestedAmount() {
        Stock stock = new Stock(5);

        Stock decreased = stock.decrease(2);

        assertThat(decreased.getQuantity()).isEqualTo(3);
        assertThat(stock.getQuantity()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveDecreaseAmount(int quantity) {
        Stock stock = new Stock(5);

        assertThatIllegalArgumentException().isThrownBy(() -> stock.decrease(quantity));
        assertThat(stock.getQuantity()).isEqualTo(5);
    }

    @Test
    void rejectsDecreaseGreaterThanAvailableQuantityWithoutChangingStock() {
        Stock stock = new Stock(5);

        assertThatIllegalArgumentException().isThrownBy(() -> stock.decrease(6));
        assertThat(stock.getQuantity()).isEqualTo(5);
    }

    @Test
    void changesQuantityToRequestedValue() {
        Stock stock = new Stock(5);

        assertThat(stock.changeTo(0).getQuantity()).isZero();
    }

    @Test
    void rejectsNegativeFinalQuantity() {
        Stock stock = new Stock(5);

        assertThatIllegalArgumentException().isThrownBy(() -> stock.changeTo(-1));
        assertThat(stock.getQuantity()).isEqualTo(5);
    }
}
