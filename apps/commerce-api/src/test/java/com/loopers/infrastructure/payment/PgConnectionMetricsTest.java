package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.support.payment.FakePaymentServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Deadline;
import org.apache.hc.core5.util.DeadlineTimeoutException;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgConnectionMetricsTest {

    @Test
    void measuresThePoolWaitBeforeAnotherRequestCanReachPg() throws Exception {
        MockClock clock = new MockClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try (FakePaymentServer pg = new FakePaymentServer();
             TestPgPaymentAdapter adapter = adapter(pg, registry, Duration.ofSeconds(2))) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            pg.beforeNextLookupReply(() -> awaitRelease(entered, release));
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> adapter.findByTransactionKey("missing"));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> adapter.findByTransactionKey("missing"));
                awaitPending(registry);
                assertThat(registry.get("httpcomponents.httpclient.pool.total.connections").tag("state", "leased")
                    .gauge().value()).isEqualTo(1);
                assertThat(pg.lookupCalls()).isEqualTo(1);
                clock.add(Duration.ofMillis(200));
                release.countDown();

                assertThat(first.get(3, TimeUnit.SECONDS).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
                assertThat(second.get(3, TimeUnit.SECONDS).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
                Timer acquired = registry.get("commerce.pg.pool.acquire").tag("result", "acquired").timer();
                assertThat(acquired.count()).isEqualTo(2);
                assertThat(acquired.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200);
                assertThat(pg.lookupCalls()).isEqualTo(2);
                assertThat(registry.get("httpcomponents.httpclient.pool.total.pending").gauge().value()).isZero();
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void aPoolTimeoutHasItsOwnTimingAndDoesNotReachPg() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FakePaymentServer pg = new FakePaymentServer();
             TestPgPaymentAdapter adapter = adapter(pg, registry, Duration.ofMillis(100))) {
            PaymentProvider provider = new TimedPaymentProvider(adapter, registry);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            pg.beforeNextLookupReply(() -> awaitRelease(entered, release));
            var executor = Executors.newSingleThreadExecutor();
            try {
                var first = executor.submit(() -> provider.findByTransactionKey("missing"));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

                assertThat(provider.findByTransactionKey("missing"))
                    .isEqualTo(PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE));
                assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "timeout").timer().count()).isEqualTo(1);
                assertThat(registry.get("commerce.pg.call").tag("result", "CONNECTION_UNAVAILABLE").timer().count()).isEqualTo(1);
                assertThat(pg.lookupCalls()).isEqualTo(1);
                release.countDown();
                assertThat(first.get(3, TimeUnit.SECONDS).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void aFutureDeadlineIsTimedAsTimeoutWithoutChangingTheThrownException() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            var pool = mock(PoolingHttpClientConnectionManager.class);
            var pending = mock(LeaseRequest.class);
            HttpRoute route = new HttpRoute(new HttpHost("http", "127.0.0.1", 18080));
            Timeout timeout = Timeout.ofSeconds(1);
            var failure = new ExecutionException(DeadlineTimeoutException.from(Deadline.MIN_VALUE));
            when(pool.lease("id", route, timeout, null)).thenReturn(pending);
            when(pending.get(timeout)).thenThrow(failure);
            try (var manager = new TimedPgConnectionManager(pool, registry, "test")) {
                assertThatThrownBy(() -> manager.lease("id", route, timeout, null).get(timeout)).isSameAs(failure);
                assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "timeout").timer().count()).isEqualTo(1);
                assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "error").timer().count()).isZero();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void cancellationRecordsOnlyOneSampleEvenWhenGetAlsoFails() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            PoolingHttpClientConnectionManager pool = mock(PoolingHttpClientConnectionManager.class);
            LeaseRequest pending = mock(LeaseRequest.class);
            HttpRoute route = new HttpRoute(new HttpHost("http", "127.0.0.1", 18080));
            Timeout timeout = Timeout.ofSeconds(1);
            InterruptedException cancelled = new InterruptedException("cancelled");
            when(pool.lease("id", route, timeout, null)).thenReturn(pending);
            when(pending.cancel()).thenReturn(true);
            when(pending.get(timeout)).thenThrow(cancelled);
            try (TimedPgConnectionManager manager = new TimedPgConnectionManager(pool, registry, "test")) {
                LeaseRequest request = manager.lease("id", route, timeout, null);
                assertThat(request.cancel()).isTrue();
                assertThatThrownBy(() -> request.get(timeout)).isSameAs(cancelled);
                assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "cancelled").timer().count()).isEqualTo(1);
                assertThat(registry.get("commerce.pg.pool.acquire").tag("result", "error").timer().count()).isZero();
            }
        } finally {
            registry.close();
        }
    }

    private static TestPgPaymentAdapter adapter(FakePaymentServer pg, MeterRegistry registry, Duration connectionWait) {
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        TimedPgConnectionManager timed = new TimedPgConnectionManager(pool, registry, "test");
        return new TestPgPaymentAdapter(pg.baseUrl(), "http://127.0.0.1:8080/callback", connectionWait,
            Duration.ofSeconds(2), Duration.ofSeconds(2), HttpClients.custom().setConnectionManager(timed));
    }

    private static void awaitPending(MeterRegistry registry) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (registry.get("httpcomponents.httpclient.pool.total.pending").gauge().value() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(registry.get("httpcomponents.httpclient.pool.total.pending").gauge().value()).isEqualTo(1);
    }

    private static void awaitRelease(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("PG response release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
