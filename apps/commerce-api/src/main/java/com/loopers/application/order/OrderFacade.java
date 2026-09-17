package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserService userService;
    private final ProductService productService;
    private final OrderService orderService;
    private final OrderConfirmationService orderConfirmationService;

    @Transactional
    public OrderInfo create(Long userId, List<OrderLine> lines) {
        userService.get(userId);
        Map<Long, Integer> quantities = aggregateQuantities(lines);
        List<OrderItem> items = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            ProductModel product = productService.get(entry.getKey());
            items.add(new OrderItem(product.getId(), product.getName(), product.getPrice(), entry.getValue()));
        }
        return OrderInfo.from(orderService.create(userId, items));
    }

    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        return OrderInfo.from(orderConfirmationService.confirm(userId, orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getMyOrders(Long userId) {
        userService.get(userId);
        return orderService.getByUser(userId).stream().map(OrderInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userService.get(userId);
        OrderModel order = orderService.get(orderId);
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
        return OrderInfo.from(order);
    }

    private static Map<Long, Integer> aggregateQuantities(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("주문 품목은 하나 이상이어야 합니다.");
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("주문 품목이 올바르지 않습니다.");
            }
            quantities.merge(line.productId(), line.quantity(), Math::addExact);
        }
        return quantities;
    }

    public record OrderLine(Long productId, int quantity) {}
}
