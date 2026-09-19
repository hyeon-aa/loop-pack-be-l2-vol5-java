package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;

import java.util.List;

public class OrderV1Dto {

    public record CreateRequest(List<OrderLineRequest> items) {}

    public record OrderLineRequest(Long productId, int quantity) {}

    public record OrderResponse(
        Long id,
        String status,
        long totalAmount,
        long paidAmount,
        List<OrderItemResponse> items
    ) {
        public static OrderResponse from(OrderInfo order) {
            return new OrderResponse(
                order.id(),
                order.status(),
                order.totalAmount(),
                order.paidAmount(),
                order.items().stream().map(OrderItemResponse::from).toList()
            );
        }
    }

    public record OrderItemResponse(Long productId, String productName, long unitPrice, int quantity) {
        public static OrderItemResponse from(OrderInfo.OrderItemInfo item) {
            return new OrderItemResponse(
                item.productId(), item.productName(), item.unitPrice(), item.quantity()
            );
        }
    }

    public record AdminOrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        long paidAmount,
        List<OrderItemResponse> items
    ) {
        public static AdminOrderResponse from(OrderInfo order) {
            return new AdminOrderResponse(
                order.id(),
                order.userId(),
                order.status(),
                order.totalAmount(),
                order.paidAmount(),
                order.items().stream().map(OrderItemResponse::from).toList()
            );
        }
    }
}
