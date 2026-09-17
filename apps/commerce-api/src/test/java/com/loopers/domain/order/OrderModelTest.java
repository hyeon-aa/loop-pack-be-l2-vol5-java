package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderModelTest {

    @Test
    void createsDraftWithSnapshotTotal() {
        OrderModel order = new OrderModel(1L, List.of(
            new OrderItem(10L, "티셔츠", 2_000L, 2),
            new OrderItem(20L, "모자", 3_000L, 1)
        ));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(7_000L);
        assertThat(order.getPaidAmount()).isZero();
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void confirmsDraftWithStoredTotal() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(4_000L);
    }

    @Test
    void rejectsSecondConfirmation() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));
        order.confirm();

        assertThatThrownBy(order::confirm).isInstanceOf(CoreException.class);
        assertThat(order.getPaidAmount()).isEqualTo(4_000L);
    }

    @Test
    void rejectsEmptyOrderAndInvalidItem() {
        assertThatThrownBy(() -> new OrderModel(1L, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItem(10L, "티셔츠", 2_000L, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
