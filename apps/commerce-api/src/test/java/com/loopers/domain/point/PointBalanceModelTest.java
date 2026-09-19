package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointBalanceModelTest {

    @Test
    void expiresOnlyRemainingRewardAtItsExactExpirationTime() {
        ZonedDateTime grantedAt = ZonedDateTime.parse("2026-09-17T14:30:00+09:00[Asia/Seoul]");
        PointBalanceModel pointBalance = new PointBalanceModel(1L);

        pointBalance.charge(1_000L, grantedAt);
        pointBalance.reward(500L, grantedAt);
        pointBalance.use(300L, grantedAt.plusMinutes(1));
        pointBalance.expire(grantedAt.plusYears(1));

        assertThat(pointBalance.getBalance()).isEqualTo(1_000L);
    }

    @Test
    void keepsRewardUsableUntilJustBeforeItsExpirationAndExpiresAtTheExactTime() {
        ZonedDateTime grantedAt = ZonedDateTime.parse("2026-09-17T14:30:00+09:00[Asia/Seoul]");
        PointBalanceModel pointBalance = new PointBalanceModel(1L);
        pointBalance.reward(500L, grantedAt);

        pointBalance.expire(grantedAt.plusYears(1).minusNanos(1));

        assertThat(pointBalance.getBalance()).isEqualTo(500L);

        pointBalance.expire(grantedAt.plusYears(1));

        assertThat(pointBalance.getBalance()).isZero();
    }

    @Test
    void usesEarlierExpirationFirst() {
        ZonedDateTime firstGrantedAt = ZonedDateTime.parse("2026-09-17T14:30:00+09:00[Asia/Seoul]");
        ZonedDateTime secondGrantedAt = firstGrantedAt.plusDays(10);
        PointBalanceModel pointBalance = new PointBalanceModel(1L);
        pointBalance.reward(500L, firstGrantedAt);
        pointBalance.reward(1_000L, secondGrantedAt);

        pointBalance.use(600L, secondGrantedAt.plusMinutes(1));
        pointBalance.expire(firstGrantedAt.plusYears(1));

        assertThat(pointBalance.getBalance()).isEqualTo(900L);
    }

    @Test
    void neverExpiresChargeAndDoesNotExpireTheSameRewardTwice() {
        ZonedDateTime grantedAt = ZonedDateTime.parse("2026-09-17T14:30:00+09:00[Asia/Seoul]");
        PointBalanceModel pointBalance = new PointBalanceModel(1L);
        pointBalance.charge(1_000L, grantedAt);
        pointBalance.reward(500L, grantedAt);

        pointBalance.expire(grantedAt.plusYears(1));
        pointBalance.expire(grantedAt.plusYears(1).plusMinutes(1));

        assertThat(pointBalance.getBalance()).isEqualTo(1_000L);
    }

    @Test
    void chargesAndUsesPointBalance() {
        PointBalanceModel pointBalance = new PointBalanceModel(1L);

        pointBalance.charge(10_000L);
        pointBalance.use(7_000L);

        assertThat(pointBalance.getBalance()).isEqualTo(3_000L);
    }

    @Test
    void keepsBalanceWhenUseExceedsAvailableBalance() {
        PointBalanceModel pointBalance = new PointBalanceModel(1L);
        pointBalance.charge(1_000L);

        assertThatThrownBy(() -> pointBalance.use(1_001L)).isInstanceOf(CoreException.class);

        assertThat(pointBalance.getBalance()).isEqualTo(1_000L);
    }
}
