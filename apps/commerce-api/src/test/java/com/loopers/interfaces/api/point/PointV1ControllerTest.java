package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointBalanceInfo;
import com.loopers.application.point.PointFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PointV1Controller.class)
class PointV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PointFacade pointFacade;

    @Test
    void returnsBalanceForRequester() throws Exception {
        when(pointFacade.getBalance(1L)).thenReturn(new PointBalanceInfo(3_000L));

        mockMvc.perform(get("/api/v1/points").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(3_000));
    }

    @Test
    void chargesPointAndReturnsUpdatedBalance() throws Exception {
        when(pointFacade.charge(1L, 1_000L)).thenReturn(new PointBalanceInfo(4_000L));

        mockMvc.perform(post("/api/v1/points/charge")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(4_000));

        verify(pointFacade).charge(1L, 1_000L);
    }
}
