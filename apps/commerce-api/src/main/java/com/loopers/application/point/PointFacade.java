package com.loopers.application.point;

import com.loopers.domain.point.PointBalanceService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointFacade {

    private final UserService userService;
    private final PointBalanceService pointBalanceService;

    @Transactional
    public PointBalanceInfo getBalance(Long userId) {
        userService.get(userId);
        return new PointBalanceInfo(pointBalanceService.getAvailable(userId).getBalance());
    }

    @Transactional
    public PointBalanceInfo charge(Long userId, long amount) {
        userService.get(userId);
        return new PointBalanceInfo(pointBalanceService.charge(userId, amount).getBalance());
    }
}
