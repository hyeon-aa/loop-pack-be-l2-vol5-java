package com.loopers.interfaces.api.like;

import com.loopers.application.brand.BrandInfo;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.CustomerProductInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LikeV1Controller.class)
class LikeV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeFacade likeFacade;

    @Test
    void returnsCreatedForNewLike() throws Exception {
        when(likeFacade.register(1L, 10L)).thenReturn(true);

        mockMvc.perform(post("/api/v1/products/10/likes").header("X-USER-ID", "1"))
            .andExpect(status().isCreated());

        verify(likeFacade).register(1L, 10L);
    }

    @Test
    void returnsOkForRepeatedLike() throws Exception {
        when(likeFacade.register(1L, 10L)).thenReturn(false);

        mockMvc.perform(post("/api/v1/products/10/likes").header("X-USER-ID", "1"))
            .andExpect(status().isOk());
    }

    @Test
    void returnsNoContentForCancellation() throws Exception {
        mockMvc.perform(delete("/api/v1/products/10/likes").header("X-USER-ID", "1"))
            .andExpect(status().isNoContent());

        verify(likeFacade).cancel(1L, 10L);
    }

    @Test
    void returnsMyActiveLikedProductsWithDerivedLikeCount() throws Exception {
        when(likeFacade.getUserLikes(1L, 1L)).thenReturn(List.of(
            new CustomerProductInfo(10L, new BrandInfo(2L, "나이키", null), "운동화", 100_000L, 3L, null)
        ));

        mockMvc.perform(get("/api/v1/users/1/likes").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(10))
            .andExpect(jsonPath("$.data[0].likeCount").value(3));
    }
}
