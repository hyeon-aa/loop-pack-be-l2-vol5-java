package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/** adapter의 호출 시도 전체를 잰다. 풀 획득·연결·통신·응답 해석을 포함하며 retry 대기와 회로 차단은 포함하지 않는다. */
final class TimedPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final MeterRegistry registry;

    TimedPaymentProvider(PaymentProvider delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public PaymentObservation create(PaymentRequest request) {
        return observe("create", () -> delegate.create(request));
    }

    @Override
    public PaymentObservation findByTransactionKey(String transactionKey) {
        return observe("lookup_transaction", () -> delegate.findByTransactionKey(transactionKey));
    }

    @Override
    public PaymentObservation findByOrderId(String providerOrderId) {
        return observe("lookup_order", () -> delegate.findByOrderId(providerOrderId));
    }

    private PaymentObservation observe(String operation, Supplier<PaymentObservation> action) {
        Timer.Sample sample = Timer.start(registry);
        String result = "ERROR";
        try {
            PaymentObservation observation = action.get();
            result = observation.kind() == PaymentObservation.Kind.UNKNOWN
                ? observation.unknownCause().name() : observation.kind().name();
            return observation;
        } finally {
            sample.stop(Timer.builder("commerce.pg.call").description("One PG adapter attempt, including pool acquisition")
                .tags("operation", operation, "result", result).publishPercentileHistogram().register(registry));
        }
    }
}
