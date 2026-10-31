package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/** 로컬 연결 부족의 즉시 retry만 빼는 실험 후보. 실제 retry·예산·회로 실행 흐름은 그대로 사용한다. */
final class NoPoolRetryPaymentProvider extends ResilientPaymentProvider {

    NoPoolRetryPaymentProvider(PaymentProvider delegate, LookupResilienceSettings settings, CircuitBreaker prototype) {
        super(new ShortageAsException(delegate), settings, ignoringTestShortage(prototype));
    }

    @Override
    public PaymentObservation findByTransactionKey(String key) {
        try {
            return super.findByTransactionKey(key);
        } catch (TestConnectionShortage exception) {
            return PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE);
        }
    }

    @Override
    public PaymentObservation findByOrderId(String orderId) {
        try {
            return super.findByOrderId(orderId);
        } catch (TestConnectionShortage exception) {
            return PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE);
        }
    }

    private static CircuitBreaker ignoringTestShortage(CircuitBreaker prototype) {
        var config = prototype.getCircuitBreakerConfig();
        return CircuitBreaker.of(prototype.getName(), CircuitBreakerConfig.from(config)
            .ignoreException(exception -> exception instanceof TestConnectionShortage
                || config.getIgnoreExceptionPredicate().test(exception))
            .build());
    }

    private static PaymentObservation withoutShortageResult(PaymentObservation observation) {
        if (observation.unknownCause() == UnknownCause.CONNECTION_UNAVAILABLE) {
            // main의 로컬 부족 예외와 타입을 구분해 회로에서 제외하되 retry하지 않는다.
            throw new TestConnectionShortage();
        }
        return observation;
    }

    private record ShortageAsException(PaymentProvider delegate) implements PaymentProvider {
        @Override
        public PaymentObservation create(PaymentRequest request) {
            return delegate.create(request);
        }

        @Override
        public PaymentObservation findByTransactionKey(String key) {
            return withoutShortageResult(delegate.findByTransactionKey(key));
        }

        @Override
        public PaymentObservation findByOrderId(String orderId) {
            return withoutShortageResult(delegate.findByOrderId(orderId));
        }
    }

    private static class TestConnectionShortage extends RuntimeException {
        TestConnectionShortage() {
            super(null, null, false, false);
        }
    }
}
