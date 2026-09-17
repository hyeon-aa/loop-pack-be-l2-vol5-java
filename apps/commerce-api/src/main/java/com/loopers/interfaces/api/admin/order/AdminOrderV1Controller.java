package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.AdminOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.order.OrderV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class AdminOrderV1Controller {

    private final AdminOrderFacade adminOrderFacade;

    @GetMapping
    public ApiResponse<List<OrderV1Dto.AdminOrderResponse>> getOrders() {
        return ApiResponse.success(
            adminOrderFacade.getAll().stream().map(OrderV1Dto.AdminOrderResponse::from).toList()
        );
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.AdminOrderResponse> getOrder(@PathVariable Long orderId) {
        return ApiResponse.success(OrderV1Dto.AdminOrderResponse.from(adminOrderFacade.get(orderId)));
    }
}
