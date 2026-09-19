package com.loopers.application.like;

import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductQueryService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final UserService userService;
    private final ProductService productService;
    private final ProductQueryService productQueryService;
    private final LikeService likeService;

    @Transactional
    public boolean register(Long userId, Long productId) {
        userService.get(userId);
        productService.get(productId);
        return likeService.register(userId, productId);
    }

    @Transactional
    public void cancel(Long userId, Long productId) {
        userService.get(userId);
        likeService.cancel(userId, productId);
    }

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> getUserLikes(Long requesterId, Long userId) {
        userService.get(requesterId);
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return productQueryService.getActiveByIdsInOrder(
            likeService.getUserLikes(userId).stream().map(like -> like.getProductId()).toList()
        );
    }
}
