package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.payment.LookupSettingsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LimitedLookupPaymentProviderTest {

    @Test
    void rejectionSkipsRetryAndCircuitWhileCreateAndLaterLookupsStillWork() throws Exception {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var observed = PaymentObservation.observed(List.of());
        when(delegate.findByTransactionKey("first")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return observed;
        });
        when(delegate.findByOrderId("next")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return observed;
        });
        when(delegate.create(any())).thenReturn(observed);
        var resilient = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        var meters = new SimpleMeterRegistry();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var limited = new LimitedLookupPaymentProvider(resilient, 1, meters, "test");
            var first = workers.submit(() -> limited.findByTransactionKey("first"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(limited.findByOrderId("next"))
                    .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.CONCURRENCY_LIMIT));
                assertThat(limited.create(new PaymentRequest("LP-ORDER", "TEST", "0000-0000-0000-0000", 1000)))
                    .isEqualTo(observed);
                assertThat(calls.get()).isEqualTo(1);
                assertThat(resilient.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
                assertThat(meters.get("commerce.pg.lookup.rejected").counter().count()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(observed);
            assertThat(limited.findByOrderId("next")).isEqualTo(observed);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(resilient.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
        } finally {
            meters.close();
        }
    }

    @Test
    void anExceptionDoesNotLeakTheLookupPermit() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var observed = PaymentObservation.observed(List.of());
        when(delegate.findByOrderId("order")).thenThrow(new IllegalStateException("failed")).thenReturn(observed);
        var meters = new SimpleMeterRegistry();
        try {
            var limited = new LimitedLookupPaymentProvider(delegate, 1, meters, "test");
            assertThatThrownBy(() -> limited.findByOrderId("order")).isInstanceOf(IllegalStateException.class);
            assertThat(limited.findByOrderId("order")).isEqualTo(observed);
        } finally {
            meters.close();
        }
    }
}
