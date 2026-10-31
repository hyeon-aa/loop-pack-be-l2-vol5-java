package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 비교 후보. retry·회로 바깥에서 논리 조회 전체를 제한하며 기본 앱에는 연결하지 않는다. */
final class LimitedLookupPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final Semaphore permits;
    private final Counter rejected;

    LimitedLookupPaymentProvider(PaymentProvider delegate, int concurrency, MeterRegistry meters, String name) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("동시 조회 한도는 양수여야 합니다.");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(concurrency);
        this.rejected = Counter.builder("commerce.pg.lookup.rejected").tag("limiter", name).register(meters);
    }

    @Override
    public PaymentObservation create(PaymentRequest request) {
        return delegate.create(request);
    }

    @Override
    public PaymentObservation findByTransactionKey(String key) {
        return lookup(() -> delegate.findByTransactionKey(key));
    }

    @Override
    public PaymentObservation findByOrderId(String id) {
        return lookup(() -> delegate.findByOrderId(id));
    }

    private PaymentObservation lookup(Supplier<PaymentObservation> call) {
        if (!permits.tryAcquire()) {
            rejected.increment();
            return PaymentObservation.unknown(PaymentObservation.UnknownCause.CONCURRENCY_LIMIT);
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }
}
