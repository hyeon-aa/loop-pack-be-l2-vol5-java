package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LikeJpaRepository extends JpaRepository<LikeModel, Long> {
    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    List<LikeModel> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select like.productId, count(like) from LikeModel like "
        + "where like.productId in :productIds group by like.productId")
    List<Object[]> countByProductIds(@Param("productIds") List<Long> productIds);
}
