package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentMetricsTest {

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            adapter.close();
        } finally {
            pg.close();
            registry.close();
        }
    }

    @Test
    void recordsEachOperationAndPreservesTheReturnedResult() {
        PaymentProvider timed = new TimedPaymentProvider(adapter, registry);
        PaymentObservation created = timed.create(request());
        String key = created.transactions().getFirst().transactionKey();

        assertThat(timed.findByTransactionKey(key)).isEqualTo(created);
        assertThat(timed.findByOrderId(request().providerOrderId())).isEqualTo(created);
        pg.replyNext(503, "");
        assertThat(timed.findByTransactionKey(key)).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));

        assertThat(timer("create", "OBSERVED").count()).isEqualTo(1);
        assertThat(timer("lookup_transaction", "OBSERVED").count()).isEqualTo(1);
        assertThat(timer("lookup_order", "OBSERVED").count()).isEqualTo(1);
        assertThat(timer("lookup_transaction", "TEMPORARY_FAILURE").count()).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(3);
    }

    @Test
    void retriedLookupRecordsOneTimingForEachActualAttempt() {
        String key = adapter.create(request()).transactions().getFirst().transactionKey();
        ResilientPaymentProvider provider = new ResilientPaymentProvider(new TimedPaymentProvider(adapter, registry),
            LookupSettingsFixture.LEARNING, new TestClock());
        pg.replyNext(503, "");

        assertThat(provider.findByTransactionKey(key).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        assertThat(timer("lookup_transaction", "TEMPORARY_FAILURE").count()).isEqualTo(1);
        assertThat(timer("lookup_transaction", "OBSERVED").count()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void anOpenCircuitDoesNotRecordAnHttpAttempt() {
        ResilientPaymentProvider provider = new ResilientPaymentProvider(new TimedPaymentProvider(adapter, registry),
            LookupSettingsFixture.LEARNING, new TestClock());
        provider.lookupCircuitBreaker().transitionToOpenState();

        assertThat(provider.findByTransactionKey("missing"))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
        assertThat(registry.find("commerce.pg.call").meters()).isEmpty();
        assertThat(pg.lookupCalls()).isZero();
    }

    @Test
    void timingDoesNotRetryPaymentCreation() {
        ResilientPaymentProvider provider = new ResilientPaymentProvider(new TimedPaymentProvider(adapter, registry),
            LookupSettingsFixture.LEARNING, new TestClock());
        pg.replyNext(503, "");

        assertThat(provider.create(request())).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        assertThat(timer("create", "TEMPORARY_FAILURE").count()).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void recordsAndRethrowsTheSameDelegateException() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        IllegalStateException failure = new IllegalStateException("delegate failed");
        when(delegate.findByOrderId("order")).thenThrow(failure);
        PaymentProvider timed = new TimedPaymentProvider(delegate, registry);

        assertThatThrownBy(() -> timed.findByOrderId("order")).isSameAs(failure);
        assertThat(timer("lookup_order", "ERROR").count()).isEqualTo(1);
    }

    @Test
    void springWiresTheTimersInsideRetryAndExportsPoolGaugesAndHistograms() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ApplicationContextRunner().withUserConfiguration(PaymentProviderConfig.class)
                .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(MeterRegistry.class, () -> prometheus)
                .withPropertyValues(
                    "commerce.payment.pg.base-url=" + pg.baseUrl(),
                    "commerce.payment.pg.callback-url=http://127.0.0.1:8080/callback",
                    "commerce.payment.pg.connect-timeout=300ms", "commerce.payment.pg.read-timeout=1s",
                    "commerce.payment.pg.lookup-read-timeout=500ms", "commerce.payment.pg.lookup-budget=2s",
                    "commerce.payment.pg.lookup-retry.max-attempts=3", "commerce.payment.pg.lookup-retry.first-wait=100ms",
                    "commerce.payment.pg.lookup-retry.wait-multiplier=2.0", "commerce.payment.pg.lookup-circuit.window-size=4",
                    "commerce.payment.pg.lookup-circuit.minimum-calls=4", "commerce.payment.pg.lookup-circuit.failure-rate-threshold=50",
                    "commerce.payment.pg.lookup-circuit.open-wait=10s", "commerce.payment.pg.lookup-circuit.half-open-calls=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaymentProvider provider = context.getBean(PaymentProvider.class);
                    String key = provider.create(request()).transactions().getFirst().transactionKey();
                    pg.replyNext(503, "");
                    assertThat(provider.findByTransactionKey(key).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);

                    assertThat(prometheus.get("commerce.pg.call").tags("operation", "lookup_transaction", "result", "TEMPORARY_FAILURE")
                        .timer().count()).isEqualTo(1);
                    assertThat(prometheus.get("commerce.pg.call").tags("operation", "lookup_transaction", "result", "OBSERVED")
                        .timer().count()).isEqualTo(1);
                    assertThat(prometheus.get("httpcomponents.httpclient.pool.total.max").gauge().value()).isEqualTo(25);
                    assertThat(prometheus.get("httpcomponents.httpclient.pool.route.max.default").gauge().value()).isEqualTo(5);
                    assertThat(prometheus.scrape()).contains("commerce_pg_call_seconds_bucket",
                        "commerce_pg_pool_acquire_seconds_bucket", "httpcomponents_httpclient_pool_total_pending");
                });
        } finally {
            prometheus.close();
        }
    }

    private Timer timer(String operation, String result) {
        return registry.get("commerce.pg.call").tags("operation", operation, "result", result).timer();
    }

    private static PaymentRequest request() {
        return new PaymentRequest("LP-METRICS-1", "TEST", "0000-0000-0000-0000", 7000);
    }
}
