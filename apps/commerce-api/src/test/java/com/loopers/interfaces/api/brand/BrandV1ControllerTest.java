package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.config.AdminRequestInterceptor;
import com.loopers.config.WebMvcConfig;
import com.loopers.interfaces.api.admin.brand.AdminBrandV1Controller;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {BrandV1Controller.class, AdminBrandV1Controller.class})
@Import({WebMvcConfig.class, AdminRequestInterceptor.class})
class BrandV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrandFacade brandFacade;

    @Test
    void returnsActiveBrandDetail() throws Exception {
        when(brandFacade.get(1L)).thenReturn(new BrandInfo(1L, "Nike", "운동화 브랜드"));

        mockMvc.perform(get("/api/v1/brands/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.name").value("Nike"));
    }

    @Test
    void createsBrandWithCreatedStatus() throws Exception {
        when(brandFacade.create("Nike", "운동화 브랜드"))
            .thenReturn(new BrandInfo(1L, "Nike", "운동화 브랜드"));

        mockMvc.perform(post("/api-admin/v1/brands")
                .header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Nike","description":"운동화 브랜드"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(1));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(brandFacade).create(name.capture(), description.capture());
        assertThat(name.getValue()).isEqualTo("Nike");
        assertThat(description.getValue()).isEqualTo("운동화 브랜드");
    }

    @Test
    void deletesBrandWithNoContentStatus() throws Exception {
        mockMvc.perform(delete("/api-admin/v1/brands/1").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isNoContent());
    }

    @Test
    void returnsAdminBrandList() throws Exception {
        when(brandFacade.getAll()).thenReturn(java.util.List.of(new BrandInfo(1L, "Nike", null)));

        mockMvc.perform(get("/api-admin/v1/brands").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("Nike"));
    }

    @Test
    void updatesBrand() throws Exception {
        when(brandFacade.update(1L, "Adidas", "스포츠 브랜드"))
            .thenReturn(new BrandInfo(1L, "Adidas", "스포츠 브랜드"));

        mockMvc.perform(put("/api-admin/v1/brands/1")
                .header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Adidas\",\"description\":\"스포츠 브랜드\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Adidas"));
    }

    @Test
    void rejectsMissingOrNonAdminRoleOnAdminApi() throws Exception {
        mockMvc.perform(get("/api-admin/v1/brands"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api-admin/v1/brands").header("X-USER-ROLE", "USER"))
            .andExpect(status().isForbidden());
    }

}
