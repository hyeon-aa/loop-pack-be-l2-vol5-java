package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentLookupSlowCallTest {

    @Test
    void aConfiguredCircuitOpensOnSlowSuccessfulHttpRepliesWhileTheDefaultStaysClosed() throws Exception {
        try (var pg = new FakePaymentServer();
             var adapter = new TestPgPaymentAdapter(pg.baseUrl(), "http://127.0.0.1/callback", Duration.ofMillis(300),
                 Duration.ofSeconds(1), Duration.ofMillis(500))) {
            String key = adapter.create(new PaymentRequest("LP-SLOW-1", "TEST", "0000-0000-0000-0000", 7000))
                .transactions().getFirst().transactionKey();
            var settings = singleAttempt();
            var current = new ResilientPaymentProvider(adapter, settings, Clock.systemUTC());
            CircuitBreaker candidate = slowCircuit(current);
            var slow = new ResilientPaymentProvider(adapter, settings, candidate);
            pg.delayEveryResponse(Duration.ofMillis(200));

            for (int i = 0; i < 4; i++) {
                assertThat(current.findByTransactionKey(key).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
                assertThat(slow.findByTransactionKey(key).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
            }

            assertThat(current.lookupCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(candidate.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(candidate.getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(candidate.getMetrics().getNumberOfSlowCalls()).isEqualTo(4);
            assertThat(slow.findByTransactionKey(key))
                .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.CIRCUIT_OPEN));
            assertThat(pg.lookupCalls()).isEqualTo(8);
        }
    }

    @Test
    void theConfiguredCircuitStillExcludesLocalPoolTimeoutsFromBothFailureAndSlowSamples() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        when(delegate.findByOrderId("order"))
            .thenReturn(PaymentObservation.unknown(PaymentObservation.UnknownCause.CONNECTION_UNAVAILABLE));
        var defaults = new ResilientPaymentProvider(delegate, singleAttempt(), Clock.systemUTC());
        var candidate = CircuitBreaker.of("tinySlowThreshold", CircuitBreakerConfig.from(defaults.lookupCircuitBreaker().getCircuitBreakerConfig())
            .slowCallDurationThreshold(Duration.ofNanos(1)).slowCallRateThreshold(50).build());
        AtomicInteger ignored = new AtomicInteger();
        candidate.getEventPublisher().onIgnoredError(event -> ignored.incrementAndGet());
        var provider = new ResilientPaymentProvider(delegate, singleAttempt(), candidate);

        for (int i = 0; i < 4; i++) {
            assertThat(provider.findByOrderId("order"))
                .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.CONNECTION_UNAVAILABLE));
        }

        assertThat(ignored.get()).isEqualTo(4);
        assertThat(candidate.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(candidate.getMetrics().getNumberOfSlowCalls()).isZero();
        assertThat(candidate.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static CircuitBreaker slowCircuit(ResilientPaymentProvider defaults) {
        return CircuitBreaker.of("slowExample", CircuitBreakerConfig.from(defaults.lookupCircuitBreaker().getCircuitBreakerConfig())
            .slowCallDurationThreshold(Duration.ofMillis(100)).slowCallRateThreshold(50).build());
    }

    private static LookupResilienceSettings singleAttempt() {
        var base = LookupSettingsFixture.LEARNING;
        return new LookupResilienceSettings(base.budget(), base.attemptEstimate(), 1, base.firstWait(), base.waitMultiplier(),
            base.windowSize(), base.minimumCalls(), base.failureRateThreshold(), base.openWait(), base.halfOpenCalls());
    }
}
