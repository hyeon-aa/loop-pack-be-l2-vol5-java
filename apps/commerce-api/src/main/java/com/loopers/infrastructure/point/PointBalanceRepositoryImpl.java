package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.point.PointBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PointBalanceRepositoryImpl implements PointBalanceRepository {

    private final PointBalanceJpaRepository pointBalanceJpaRepository;

    @Override
    public PointBalanceModel save(PointBalanceModel pointBalance) {
        return pointBalanceJpaRepository.save(pointBalance);
    }

    @Override
    public Optional<PointBalanceModel> findByUserId(Long userId) {
        return pointBalanceJpaRepository.findByUserId(userId);
    }
}
