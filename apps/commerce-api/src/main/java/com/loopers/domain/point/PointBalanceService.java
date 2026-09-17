package com.loopers.domain.point;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
public class PointBalanceService {

    private final PointBalanceRepository pointBalanceRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PointBalanceModel getOrEmpty(Long userId) {
        return pointBalanceRepository.findByUserId(userId).orElseGet(() -> new PointBalanceModel(userId));
    }

    @Transactional
    public PointBalanceModel getAvailable(Long userId) {
        PointBalanceModel pointBalance = getOrEmpty(userId);
        pointBalance.expire(now());
        return pointBalanceRepository.save(pointBalance);
    }

    @Transactional
    public PointBalanceModel charge(Long userId, long amount) {
        PointBalanceModel pointBalance = getOrEmpty(userId);
        pointBalance.expire(now());
        pointBalance.charge(amount, now());
        return pointBalanceRepository.save(pointBalance);
    }

    @Transactional
    public PointBalanceModel use(Long userId, long amount) {
        PointBalanceModel pointBalance = getOrEmpty(userId);
        pointBalance.use(amount, now());
        return pointBalanceRepository.save(pointBalance);
    }

    @Transactional
    public PointBalanceModel reward(Long userId, long amount) {
        PointBalanceModel pointBalance = getOrEmpty(userId);
        pointBalance.reward(amount, now());
        return pointBalanceRepository.save(pointBalance);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
