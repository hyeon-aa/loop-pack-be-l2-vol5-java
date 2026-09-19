package com.loopers.interfaces.api.admin;

import com.loopers.domain.brand.BrandModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void allowsAdminAndRejectsUserOrMissingRoleThroughTheActualHttpStack() throws Exception {
        BrandModel brand = brandJpaRepository.save(new BrandModel("Nike", null));

        mockMvc.perform(get("/api-admin/v1/brands").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(brand.getId()));
        mockMvc.perform(get("/api-admin/v1/brands").header("X-USER-ROLE", "USER"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api-admin/v1/brands"))
            .andExpect(status().isForbidden());
    }

    @Test
    void persistsAdminCreateRequestThroughControllerApplicationAndRepository() throws Exception {
        mockMvc.perform(post("/api-admin/v1/brands")
                .header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Adidas\",\"description\":\"스포츠 브랜드\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("Adidas"));

        assertThat(brandJpaRepository.findByDeletedAtIsNull())
            .extracting(BrandModel::getName)
            .containsExactly("Adidas");
    }
}
