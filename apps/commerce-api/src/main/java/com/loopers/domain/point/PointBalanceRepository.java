package com.loopers.domain.point;

import java.util.Optional;

public interface PointBalanceRepository {
    PointBalanceModel save(PointBalanceModel pointBalance);

    Optional<PointBalanceModel> findByUserId(Long userId);
}
