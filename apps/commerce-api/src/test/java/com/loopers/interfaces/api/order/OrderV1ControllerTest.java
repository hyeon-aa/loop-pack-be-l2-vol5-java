package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderV1Controller.class)
class OrderV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderFacade orderFacade;

    @Test
    void createsDraftOrderWithCreatedStatus() throws Exception {
        when(orderFacade.create(1L, List.of(new OrderFacade.OrderLine(10L, 2))))
            .thenReturn(orderInfo("DRAFT", 4_000L, 0L));

        mockMvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":10,\"quantity\":2}]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.totalAmount").value(4_000));

        verify(orderFacade).create(
            eq(1L),
            argThat(lines -> lines.equals(List.of(new OrderFacade.OrderLine(10L, 2))))
        );
    }

    @Test
    void confirmsOrderAndReturnsPaidAmount() throws Exception {
        when(orderFacade.confirm(1L, 100L)).thenReturn(orderInfo("CONFIRMED", 4_000L, 4_000L));

        mockMvc.perform(post("/api/v1/orders/100/confirm").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paidAmount").value(4_000));
    }

    @Test
    void returnsOnlyMyOrders() throws Exception {
        when(orderFacade.getMyOrders(1L)).thenReturn(List.of(orderInfo("DRAFT", 4_000L, 0L)));

        mockMvc.perform(get("/api/v1/orders").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("DRAFT"));
    }

    private static OrderInfo orderInfo(String status, long totalAmount, long paidAmount) {
        return new OrderInfo(100L, 1L, status, totalAmount, paidAmount, List.of());
    }
}
