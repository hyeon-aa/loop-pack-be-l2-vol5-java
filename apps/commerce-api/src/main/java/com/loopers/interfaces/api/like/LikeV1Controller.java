package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.product.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class LikeV1Controller {

    private final LikeFacade likeFacade;

    @PostMapping("/products/{productId}/likes")
    public ResponseEntity<Void> register(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long productId
    ) {
        return likeFacade.register(userId, productId)
            ? ResponseEntity.status(HttpStatus.CREATED).build()
            : ResponseEntity.ok().build();
    }

    @DeleteMapping("/products/{productId}/likes")
    public ResponseEntity<Void> cancel(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long productId
    ) {
        likeFacade.cancel(userId, productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/likes")
    public ApiResponse<List<ProductV1Dto.ProductResponse>> getUserLikes(
        @RequestHeader("X-USER-ID") Long requesterId,
        @PathVariable Long userId
    ) {
        List<ProductV1Dto.ProductResponse> response = likeFacade.getUserLikes(requesterId, userId).stream()
            .map(ProductV1Dto.ProductResponse::from)
            .toList();
        return ApiResponse.success(response);
    }
}
