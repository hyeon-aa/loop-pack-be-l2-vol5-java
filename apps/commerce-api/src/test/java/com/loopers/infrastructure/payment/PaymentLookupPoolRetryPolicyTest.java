package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.payment.LookupSettingsFixture;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentLookupPoolRetryPolicyTest {

    @Test
    void defaultStillRetriesLocalShortageWithoutRecordingCircuitSamples() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var unavailable = PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE);
        when(delegate.findByOrderId("order")).thenReturn(unavailable);
        var provider = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, Clock.systemUTC());

        assertThat(provider.findByOrderId("order")).isEqualTo(unavailable);

        verify(delegate, times(3)).findByOrderId("order");
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void candidateSkipsOnlyLocalRetryAndStillExcludesTheShortage() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var unavailable = PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE);
        when(delegate.findByOrderId("order")).thenReturn(unavailable);
        var base = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        var provider = new NoPoolRetryPaymentProvider(delegate, LookupSettingsFixture.LEARNING, base.lookupCircuitBreaker());

        assertThat(provider.findByOrderId("order")).isEqualTo(unavailable);

        verify(delegate).findByOrderId("order");
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void candidateStillRetriesPgFailureAndRecordsBothAnswers() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var observed = PaymentObservation.observed(List.of());
        when(delegate.findByTransactionKey("key"))
            .thenReturn(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE)).thenReturn(observed);
        var base = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        var provider = new NoPoolRetryPaymentProvider(delegate, LookupSettingsFixture.LEARNING, base.lookupCircuitBreaker());

        assertThat(provider.findByTransactionKey("key")).isEqualTo(observed);

        verify(delegate, times(2)).findByTransactionKey("key");
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void candidateReturnsTransactionLookupShortageWithoutRetryOrCircuitSample() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var unavailable = PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE);
        when(delegate.findByTransactionKey("key")).thenReturn(unavailable);
        var base = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        var provider = new NoPoolRetryPaymentProvider(delegate, LookupSettingsFixture.LEARNING, base.lookupCircuitBreaker());

        assertThat(provider.findByTransactionKey("key")).isEqualTo(unavailable);

        verify(delegate).findByTransactionKey("key");
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void candidatePassesCreateThroughOnceWithoutRecordingCircuitSample() {
        PaymentProvider delegate = mock(PaymentProvider.class);
        var request = new PaymentRequest("LP-ORDER", "TEST", "0000-0000-0000-0000", 7000);
        var observed = PaymentObservation.observed(List.of());
        when(delegate.create(request)).thenReturn(observed);
        var base = new ResilientPaymentProvider(delegate, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        var provider = new NoPoolRetryPaymentProvider(delegate, LookupSettingsFixture.LEARNING, base.lookupCircuitBreaker());

        assertThat(provider.create(request)).isEqualTo(observed);

        verify(delegate).create(request);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }
}
