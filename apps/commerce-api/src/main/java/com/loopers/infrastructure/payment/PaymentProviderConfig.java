package com.loopers.infrastructure.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class PaymentProviderConfig {

    // Actuator 없이 PG 설정만 독립 기동할 때는 빈 composite로 측정을 내보내지 않는다.
    private final MeterRegistry fallbackRegistry = new CompositeMeterRegistry();

    @Bean(name = "paymentProvider")
    TestPgPaymentAdapter meteredPaymentProvider(
        @Value("${commerce.payment.pg.base-url}") String baseUrl,
        @Value("${commerce.payment.pg.callback-url}") String callbackUrl,
        @Value("${commerce.payment.pg.connect-timeout}") Duration connectTimeout,
        @Value("${commerce.payment.pg.read-timeout}") Duration readTimeout,
        @Value("${commerce.payment.pg.lookup-read-timeout}") Duration lookupReadTimeout,
        ObjectProvider<MeterRegistry> registries
    ) {
        MeterRegistry registry = registries.getIfAvailable(() -> fallbackRegistry);
        var pool = PoolingHttpClientConnectionManagerBuilder.create().build();
        var timed = new TimedPgConnectionManager(pool, registry, "shared");
        return new TestPgPaymentAdapter(baseUrl, callbackUrl, connectTimeout, readTimeout, lookupReadTimeout,
            HttpClients.custom().setConnectionManager(timed));
    }

    /** Spring 없이 HTTP 계약·기존 설정 비교에 쓰는 비계측 adapter. */
    TestPgPaymentAdapter paymentProvider(String baseUrl, String callbackUrl, Duration connectTimeout,
                                         Duration readTimeout, Duration lookupReadTimeout) {
        return new TestPgPaymentAdapter(baseUrl, callbackUrl, connectTimeout, readTimeout, lookupReadTimeout);
    }

    /**
     * 주문 흐름이 쓰는 PG 포트. 조회만 retry와 회로로 감싼다.
     * 연결 풀 대기·연결·응답 대기 설정의 합을 다음 재시도 판단용 추정치로 쓴다. 실제 호출 전체의 최대 시간 보장은 아니다.
     */
    @Bean
    @Primary
    ResilientPaymentProvider resilientPaymentProvider(
        TestPgPaymentAdapter adapter,
        @Value("${commerce.payment.pg.connect-timeout}") Duration connectTimeout,
        @Value("${commerce.payment.pg.lookup-read-timeout}") Duration lookupReadTimeout,
        @Value("${commerce.payment.pg.lookup-budget}") Duration lookupBudget,
        @Value("${commerce.payment.pg.lookup-retry.max-attempts}") int maxAttempts,
        @Value("${commerce.payment.pg.lookup-retry.first-wait}") Duration firstWait,
        @Value("${commerce.payment.pg.lookup-retry.wait-multiplier}") double waitMultiplier,
        @Value("${commerce.payment.pg.lookup-circuit.window-size}") int windowSize,
        @Value("${commerce.payment.pg.lookup-circuit.minimum-calls}") int minimumCalls,
        @Value("${commerce.payment.pg.lookup-circuit.failure-rate-threshold}") float failureRateThreshold,
        @Value("${commerce.payment.pg.lookup-circuit.open-wait}") Duration openWait,
        @Value("${commerce.payment.pg.lookup-circuit.half-open-calls}") int halfOpenCalls,
        ObjectProvider<MeterRegistry> registries
    ) {
        Duration attemptEstimate = connectTimeout.multipliedBy(2).plus(lookupReadTimeout);
        LookupResilienceSettings settings = new LookupResilienceSettings(lookupBudget, attemptEstimate,
            maxAttempts, firstWait, waitMultiplier, windowSize, minimumCalls, failureRateThreshold, openWait, halfOpenCalls);
        return new ResilientPaymentProvider(new TimedPaymentProvider(adapter, registries.getIfAvailable(() -> fallbackRegistry)),
            settings, Clock.systemUTC());
    }
}
