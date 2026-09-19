package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;

import java.util.List;

public record OrderInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    long paidAmount,
    List<OrderItemInfo> items
) {
    public static OrderInfo from(OrderModel order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getItems().stream().map(OrderItemInfo::from).toList()
        );
    }

    public record OrderItemInfo(Long productId, String productName, long unitPrice, int quantity) {
        private static OrderItemInfo from(OrderItem item) {
            return new OrderItemInfo(
                item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity()
            );
        }
    }
}
