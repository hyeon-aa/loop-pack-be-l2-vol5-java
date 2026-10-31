package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연결을 빌리지 못한 실패를 회로가 실패로 셀 때, 성공으로 셀 때, 집계에서 뺄 때를 같은 장면에서 비교한다.
 * 회로는 결과(result)를 실패 아니면 성공으로만 기록하므로, 연결 부족은 예외로 던지고 정책마다 그 예외의 기록 방식만 바꾼다.
 * 집계 제외는 ignoreExceptions로 한다. 무시한 예외는 허가만 돌려주고 창에 넣지 않는다.
 * 세 분류 후보를 비교하려고 테스트 안에서 adapter와 같은 HTTP 설정(같은 주소당 연결 5개, 빌리기 대기 300ms,
 * 응답 대기 500ms, 자동 재시도 끔)으로 조회하고 연결 빌리기 timeout을 따로 표시한다.
 * 실제 adapter·provider의 현재 분류는 별도 계약·회로 테스트가 확인한다.
 */
@Tag("experiment")
class PaymentLookupPoolFailureCountingExperimentTest {

    private static final int CONCURRENT = 20;
    private static final int POOL_PER_ROUTE = 5;
    private static final LookupResilienceSettings SETTINGS = LookupSettingsFixture.LEARNING;

    private FakePaymentServer pg;
    private CloseableHttpClient httpClient;
    private RestClient lookupClient;
    private String transactionKey;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        try (TestPgPaymentAdapter adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(),
            "http://127.0.0.1:8080/api/v1/payments/callback", Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500))) {
            transactionKey = adapter.create(new PaymentRequest("LP-POOL-COUNT-0", "TEST", "0000-0000-0000-0000", 7000))
                .transactions().getFirst().transactionKey();
        }
        httpClient = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(Duration.ofMillis(300));
        factory.setConnectionRequestTimeout(Duration.ofMillis(300));
        factory.setReadTimeout(Duration.ofMillis(500));
        lookupClient = RestClient.builder().baseUrl(pg.baseUrl()).requestFactory(factory)
            .defaultHeader("X-USER-ID", "135135").build();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            httpClient.close();
        } finally {
            pg.close();
        }
    }

    @ParameterizedTest(name = "pg={0}ms, poolFailure={1}")
    @CsvSource({"400, FAILURE", "400, SUCCESS", "400, IGNORED", "2000, FAILURE", "2000, SUCCESS", "2000, IGNORED"})
    void comparesCountingConnectionShortageAsPgFailure(long pgResponseMillis, PoolFailureRecording recording) throws Exception {
        Outcome outcome = run(pgResponseMillis, false, SETTINGS.windowSize(), recording);

        assertThat(outcome.poolFailures()).isPositive();
        assertThat(outcome.ignored()).isEqualTo(recording == PoolFailureRecording.IGNORED ? outcome.poolFailures() : 0);
        if (pgResponseMillis < 500) {
            // PG가 정상이면, 연결 부족을 실패로 세는 정책만 회로를 연다.
            assertThat(outcome.state()).isEqualTo(recording == PoolFailureRecording.FAILURE ? State.OPEN : State.CLOSED);
            assertThat(outcome.pgFailures()).isZero();
            assertThat(outcome.verified()).isGreaterThanOrEqualTo(POOL_PER_ROUTE);
        } else {
            // PG가 느리면, 연결 부족을 실패로 세지 않아도 PG까지 간 요청의 timeout으로 회로가 열린다.
            assertThat(outcome.state()).isEqualTo(State.OPEN);
            assertThat(outcome.verified()).isZero();
        }
    }

    /**
     * PG가 400ms 뒤 503을 주는 장면과, 창을 20으로 넓혀(최소 표본 4는 유지) 과거 표본을 오래 기억하는 장면.
     * 창 20에서는 성공으로 센 연결 부족이 창에 남아 실제 PG 실패(503·timeout)의 실패율을 25%로 낮춰 회로가 열리지 않았다.
     */
    @ParameterizedTest(name = "pg={0}ms, 503={1}, window={2}, poolFailure={3} -> {4}")
    @CsvSource({
        "400, true, 4, FAILURE, OPEN", "400, true, 4, SUCCESS, OPEN", "400, true, 4, IGNORED, OPEN",
        "400, false, 20, FAILURE, OPEN", "400, false, 20, SUCCESS, CLOSED", "400, false, 20, IGNORED, CLOSED",
        "2000, false, 20, FAILURE, OPEN", "2000, false, 20, SUCCESS, CLOSED", "2000, false, 20, IGNORED, OPEN",
        "400, true, 20, FAILURE, OPEN", "400, true, 20, SUCCESS, CLOSED", "400, true, 20, IGNORED, OPEN"
    })
    void comparesPgFailureDetectionInWiderWindow(long pgResponseMillis, boolean pgReturns503, int windowSize,
                                                 PoolFailureRecording recording, State expectedState) throws Exception {
        Outcome outcome = run(pgResponseMillis, pgReturns503, windowSize, recording);

        assertThat(outcome.state()).isEqualTo(expectedState);
        assertThat(outcome.poolFailures()).isPositive();
        assertThat(outcome.ignored()).isEqualTo(recording == PoolFailureRecording.IGNORED ? outcome.poolFailures() : 0);
        if (pgReturns503 || pgResponseMillis > 500) {
            assertThat(outcome.pgFailures()).isPositive();
            if (recording == PoolFailureRecording.IGNORED) {
                // 연결 부족이 실제로 발생했어도 성공·실패 표본에는 PG 실패만 남아야 한다.
                assertThat(outcome.successes()).isZero();
                assertThat(outcome.buffered()).isEqualTo(outcome.failures());
                assertThat(outcome.failureRate()).isEqualTo(100.0f);
            } else if (recording == PoolFailureRecording.SUCCESS && windowSize == 20) {
                assertThat(outcome.successes()).isPositive();
                assertThat(outcome.failureRate()).isLessThan(SETTINGS.failureRateThreshold());
            }
        }
    }

    private Outcome run(long pgResponseMillis, boolean pgReturns503, int windowSize, PoolFailureRecording recording)
        throws Exception {
        pg.delayEveryResponse(Duration.ofMillis(pgResponseMillis));
        if (pgReturns503) {
            for (int i = 0; i < 100; i++) {
                pg.replyNext(503, "");
            }
        }
        CircuitBreaker circuit = circuitBreaker(windowSize, recording);
        AtomicInteger ignored = new AtomicInteger();
        circuit.getEventPublisher().onIgnoredError(event -> ignored.incrementAndGet());
        AtomicLong openedAtNanos = new AtomicLong();
        AtomicLong failureRateAtOpen = new AtomicLong(-1);
        circuit.getEventPublisher().onFailureRateExceeded(event ->
            failureRateAtOpen.compareAndSet(-1, Math.round(event.getFailureRate())));
        circuit.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == State.OPEN) {
                openedAtNanos.compareAndSet(0, System.nanoTime());
            }
        });
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger poolFailures = new AtomicInteger();
        AtomicInteger pgFailures = new AtomicInteger();

        long started = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT);
        List<Future<Attempt>> futures = new ArrayList<>();
        CountDownLatch firstWave = new CountDownLatch(CONCURRENT);
        try {
            futures.addAll(start(executor, POOL_PER_ROUTE, circuit, attempts, poolFailures, pgFailures, firstWave));
            awaitLookupsReceived(POOL_PER_ROUTE);
            futures.addAll(start(executor, CONCURRENT - POOL_PER_ROUTE, circuit, attempts, poolFailures, pgFailures, firstWave));

            int verified = 0;
            int blocked = 0;
            int exhausted = 0;
            for (Future<Attempt> future : futures) {
                Attempt result = future.get(10, TimeUnit.SECONDS);
                switch (result) {
                    case OBSERVED -> verified++;
                    case BLOCKED -> blocked++;
                    default -> exhausted++;
                }
            }
            long openedAtMillis = openedAtNanos.get() == 0 ? -1 : Duration.ofNanos(openedAtNanos.get() - started).toMillis();
            // OPEN이면 열리던 순간의 실패율, 끝까지 CLOSED면 마지막 창의 실패율(표본이 모자라면 -1)
            float failureRate = failureRateAtOpen.get() >= 0 ? failureRateAtOpen.get() : circuit.getMetrics().getFailureRate();
            Outcome outcome = new Outcome(verified, blocked, exhausted, attempts.get(), poolFailures.get(), pgFailures.get(),
                ignored.get(), pg.lookupCalls(), circuit.getState(), openedAtMillis, circuit.getMetrics().getNumberOfBufferedCalls(),
                circuit.getMetrics().getNumberOfSuccessfulCalls(), circuit.getMetrics().getNumberOfFailedCalls(), failureRate);
            System.out.printf("pool-count-experiment,pg=%dms,503=%s,window=%d,poolFailure=%s,verified=%d,blockedByCircuit=%d,"
                    + "retryExhausted=%d,attempts=%d,poolFailures=%d,pgFailures=%d,ignored=%d,pgReceived=%d,circuit=%s,"
                    + "openedAtMs=%d,failureRate=%.0f,buffered=%d%n",
                pgResponseMillis, pgReturns503, windowSize, recording, verified, blocked, exhausted, outcome.attempts(),
                outcome.poolFailures(), outcome.pgFailures(), outcome.ignored(), outcome.pgReceived(), outcome.state(),
                openedAtMillis, failureRate, circuit.getMetrics().getNumberOfBufferedCalls());
            return outcome;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Future<Attempt>> start(ExecutorService executor, int count, CircuitBreaker circuit,
                                        AtomicInteger attempts, AtomicInteger poolFailures, AtomicInteger pgFailures,
                                        CountDownLatch firstWave) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                return lookupWithPolicy(circuit, attempts, poolFailures, pgFailures, firstWave);
            }));
        }
        go.countDown();
        return futures;
    }

    /** 같은 retry 조건·순서를 쓰되, 비교를 위해 최초 20건의 집계 뒤 retry를 허용한다. 앱에 없는 실험용 경계다. */
    private Attempt lookupWithPolicy(CircuitBreaker circuit, AtomicInteger attempts,
                                     AtomicInteger poolFailures, AtomicInteger pgFailures, CountDownLatch firstWave) {
        long deadline = System.nanoTime() + SETTINGS.budget().toNanos();
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        Retry retry = Retry.of("poolCountExperiment", RetryConfig.<Attempt>custom()
            .maxAttempts(SETTINGS.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(SETTINGS.firstWait(), SETTINGS.waitMultiplier()))
            .retryOnResult(result -> result == Attempt.PG_FAILURE && hasTimeForAnotherAttempt(deadline))
            .retryOnException(exception -> exception instanceof PoolExhaustedException && hasTimeForAnotherAttempt(deadline))
            .build());
        try {
            return retry.executeSupplier(() -> {
                try {
                    return circuit.executeSupplier(() -> {
                        attempts.incrementAndGet();
                        Attempt result = getOnce();
                        if (result == Attempt.POOL_EXHAUSTED) {
                            poolFailures.incrementAndGet();
                            throw new PoolExhaustedException();
                        }
                        if (result == Attempt.PG_FAILURE) {
                            pgFailures.incrementAndGet();
                        }
                        return result;
                    });
                } finally {
                    if (firstAttempt.compareAndSet(true, false)) {
                        awaitFirstWave(firstWave);
                    }
                }
            });
        } catch (CallNotPermittedException exception) {
            return Attempt.BLOCKED;
        } catch (PoolExhaustedException exception) {
            return Attempt.POOL_EXHAUSTED;
        }
    }

    private static void awaitFirstWave(CountDownLatch firstWave) {
        firstWave.countDown();
        try {
            if (!firstWave.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("First lookup wave did not finish");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Attempt getOnce() {
        try {
            lookupClient.get().uri("/api/v1/payments/{transactionKey}", transactionKey).retrieve().toBodilessEntity();
            return Attempt.OBSERVED;
        } catch (ResourceAccessException exception) {
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (TestPgPaymentAdapter.isConnectionAcquisitionTimeout(cause)) {
                    return Attempt.POOL_EXHAUSTED;
                }
            }
            return Attempt.PG_FAILURE;
        } catch (HttpServerErrorException exception) {
            return Attempt.PG_FAILURE;
        }
    }

    private static boolean hasTimeForAnotherAttempt(long deadline) {
        Duration longestWait = Duration.ofMillis(200);
        return deadline - System.nanoTime() >= longestWait.plus(SETTINGS.attemptEstimate()).toNanos();
    }

    private static CircuitBreaker circuitBreaker(int windowSize, PoolFailureRecording recording) {
        CircuitBreakerConfig.Builder config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(windowSize)
            .minimumNumberOfCalls(SETTINGS.minimumCalls())
            .failureRateThreshold(SETTINGS.failureRateThreshold())
            .waitDurationInOpenState(SETTINGS.openWait())
            .permittedNumberOfCallsInHalfOpenState(SETTINGS.halfOpenCalls())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordResult(result -> result == Attempt.PG_FAILURE)
            .clock(new TestClock());
        switch (recording) {
            case FAILURE -> { } // 회로는 기본으로 모든 예외를 실패로 기록한다.
            case SUCCESS -> config.recordException(exception -> !(exception instanceof PoolExhaustedException));
            case IGNORED -> config.ignoreExceptions(PoolExhaustedException.class);
        }
        return CircuitBreaker.of("poolCountExperiment", config.build());
    }

    private void awaitLookupsReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (pg.lookupCalls() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pg.lookupCalls()).isEqualTo(count);
    }

    private enum Attempt { OBSERVED, POOL_EXHAUSTED, PG_FAILURE, BLOCKED }

    private record Outcome(int verified, int blocked, int exhausted, int attempts, int poolFailures, int pgFailures,
                           int ignored, int pgReceived, State state, long openedAtMillis, int buffered,
                           int successes, int failures, float failureRate) {}

    /** 연결을 빌리지 못한 조회를 회로에 어떻게 남길지. */
    enum PoolFailureRecording { FAILURE, SUCCESS, IGNORED }

    private static class PoolExhaustedException extends RuntimeException {
    }
}
