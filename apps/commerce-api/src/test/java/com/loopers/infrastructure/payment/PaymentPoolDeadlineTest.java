package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.util.Deadline;
import org.apache.hc.core5.util.DeadlineTimeoutException;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentPoolDeadlineTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPoolFutureDeadlineIsLocalForCreateAndBothLookups(boolean metered) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FakePaymentServer pg = new FakePaymentServer()) {
            var pool = failedPool(new ExecutionException(DeadlineTimeoutException.from(Deadline.MIN_VALUE)));
            var manager = metered ? new TimedPgConnectionManager(pool, registry, "test") : pool;
            try (var adapter = adapter(pg, HttpClients.custom().setConnectionManager(manager))) {
                PaymentProvider delegate = metered ? new TimedPaymentProvider(adapter, registry) : adapter;
                var provider = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, new TestClock());

                assertThat(provider.create(request())).isEqualTo(PaymentObservation.notSent());
                assertThat(provider.findByTransactionKey("missing"))
                    .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.CONNECTION_UNAVAILABLE));
                assertThat(provider.findByOrderId("order"))
                    .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.CONNECTION_UNAVAILABLE));
                assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
                assertThat(pg.createCalls()).isZero();
                assertThat(pg.lookupCalls()).isZero();
                if (metered) {
                    assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "timeout").timer().count()).isEqualTo(7);
                    assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "error").timer().count()).isZero();
                    assertThat(registry.get("commerce.pg.call").tags("operation", "lookup_transaction", "result", "CONNECTION_UNAVAILABLE")
                        .timer().count()).isEqualTo(3);
                }
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void anotherLeaseExecutionFailureIsNotReclassifiedAsPoolTimeout() throws Exception {
        try (FakePaymentServer pg = new FakePaymentServer()) {
            var pool = failedPool(new ExecutionException(new IOException("other failure")));
            try (var adapter = adapter(pg, HttpClients.custom().setConnectionManager(pool))) {
                assertThat(adapter.create(request()))
                    .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
                assertThat(adapter.findByOrderId("order"))
                    .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
            }
        }
    }

    private static PoolingHttpClientConnectionManager failedPool(ExecutionException failure) throws Exception {
        var pool = mock(PoolingHttpClientConnectionManager.class);
        var lease = mock(LeaseRequest.class);
        when(pool.lease(anyString(), any(HttpRoute.class), any(Timeout.class), isNull())).thenReturn(lease);
        when(lease.get(any(Timeout.class))).thenThrow(failure);
        return pool;
    }

    private static TestPgPaymentAdapter adapter(FakePaymentServer pg, HttpClientBuilder builder) {
        return new TestPgPaymentAdapter(pg.baseUrl(), "http://127.0.0.1/callback", Duration.ofMillis(300),
            Duration.ofSeconds(1), Duration.ofMillis(500), builder);
    }

    private static PaymentRequest request() {
        return new PaymentRequest("LP-DEADLINE-1", "TEST", "0000-0000-0000-0000", 7000);
    }
}
