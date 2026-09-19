package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalanceModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointBalanceJpaRepository extends JpaRepository<PointBalanceModel, Long> {
    Optional<PointBalanceModel> findByUserId(Long userId);
}
