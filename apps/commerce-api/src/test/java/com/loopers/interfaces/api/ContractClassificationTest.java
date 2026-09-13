package com.loopers.interfaces.api;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
import com.loopers.interfaces.api.example.ExampleV1Dto;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private ExampleJpaRepository exampleJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void observeExistingId() {
        ExampleModel saved = exampleJpaRepository.save(new ExampleModel("예시 제목", "예시 설명"));

        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = testRestTemplate.exchange(
            "/api/v1/examples/" + saved.getId(), HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE
        );

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(response.getBody().meta().errorCode()).isNull(),
            () -> assertThat(response.getBody().data()).isNotNull(),
            () -> assertThat(response.getBody().data().id()).isEqualTo(saved.getId())
        );
    }

    @Test
    void observeNonNumericId() {
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = testRestTemplate.exchange(
            "/api/v1/examples/abc", HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE
        );

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(ErrorType.BAD_REQUEST.getCode()),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @Test
    void observeNonExistingId() {
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = testRestTemplate.exchange(
            "/api/v1/examples/999999", HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE
        );

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(ErrorType.NOT_FOUND.getCode()),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @Test
    void observeUnmappedUrl() {
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = testRestTemplate.exchange(
            "/api/v1/no-such-path", HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE
        );

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(ErrorType.NOT_FOUND.getCode()),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }
}
