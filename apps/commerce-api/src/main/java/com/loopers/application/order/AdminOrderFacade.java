package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class AdminOrderFacade {

    private final OrderService orderService;

    @Transactional(readOnly = true)
    public List<OrderInfo> getAll() {
        return orderService.getAll().stream().map(OrderInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo get(Long orderId) {
        return OrderInfo.from(orderService.get(orderId));
    }
}
