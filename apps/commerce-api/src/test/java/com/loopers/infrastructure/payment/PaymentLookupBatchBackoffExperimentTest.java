package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 HTTP adapter와 메모리 배치·TestClock으로 즉시 시도와 다음 실행의 정책을 분리한다. */
@Tag("experiment")
class PaymentLookupBatchBackoffExperimentTest {

    private static final int MINUTES = 15;
    private static final long MAX_BACKOFF_MINUTES = 8;
    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private TestClock clock;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        clock = new TestClock();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            adapter.close();
        } finally {
            pg.close();
        }
    }

    // 기존 9조건의 기대값을 유지하고 PG 실패만 간격을 늘리는 3조건을 추가한다.
    @ParameterizedTest(name = "outage={0}min / {1}: lastAt={2}, GET={3}, failures={4}")
    @CsvSource({
        "0, IMMEDIATE_RETRY,          0, 10, 0",
        "0, SINGLE_ATTEMPT,           0, 10, 0",
        "0, BACKOFF_ALL_UNCONFIRMED,  0, 10, 0",
        "0, BACKOFF_PG_FAILURE_ONLY,  0, 10, 0",
        "3, IMMEDIATE_RETRY,          3, 18, 8",
        "3, SINGLE_ATTEMPT,           3, 18, 8",
        "3, BACKOFF_ALL_UNCONFIRMED,  3, 16, 6",
        "3, BACKOFF_PG_FAILURE_ONLY,  4, 18, 8",
        "4, IMMEDIATE_RETRY,          4, 20, 10",
        "4, SINGLE_ATTEMPT,           4, 20, 10",
        "4, BACKOFF_ALL_UNCONFIRMED,  7, 18, 8",
        "4, BACKOFF_PG_FAILURE_ONLY,  7, 20, 10"
    })
    void separatesImmediateAttemptsFromBatchIntervals(int outageMinutes, Policy policy, int expectedLastMinute,
                                                     int expectedPgLookups, int expectedFailures) throws Exception {
        Outcome result = measure(Scene.GLOBAL_OUTAGE, outageMinutes, policy);

        assertThat(result.confirmedAt()).hasSize(10);
        assertThat(result.remaining()).isZero();
        assertThat(result.lastConfirmedMinute()).isEqualTo(expectedLastMinute);
        assertThat(result.pgLookups()).isEqualTo(expectedPgLookups);
        assertThat(result.recordedFailures()).isEqualTo(expectedFailures);
        if (policy == Policy.BACKOFF_PG_FAILURE_ONLY && outageMinutes > 0) {
            assertThat(result.distribution()).isEqualTo(outageMinutes == 3
                ? Map.of(3, 8L, 4, 2L) : Map.of(4, 8L, 7, 2L));
        } else {
            assertThat(result.distribution()).isEqualTo(Map.of(expectedLastMinute, 10L));
        }
    }

    // 문제 거래를 먼저 놓는 통제 조건이며 일반적인 거래별 업무 오류를 503으로 대표하지 않는다.
    @ParameterizedTest(name = "one failing trade / {0}: healthy={1}/45, GET={2}, failures={3}, badGET={4}")
    @CsvSource({"IMMEDIATE_RETRY, 1, 32, 31, 31", "SINGLE_ATTEMPT, 45, 60, 15, 15",
        "BACKOFF_PG_FAILURE_ONLY, 45, 49, 4, 4"})
    void separatesFailureAmplificationFromBackoffForOneFailingTrade(Policy policy, int healthyConfirmed,
                                                                   int pgLookups, int failures, int badLookups) throws Exception {
        Outcome result = measure(Scene.ONE_FAILING_TRADE, 0, policy);

        assertThat(result.confirmedAt()).hasSize(healthyConfirmed);
        assertThat(result.firstSweepConfirmed()).isEqualTo(healthyConfirmed);
        assertThat(result.firstSweepFailures()).isEqualTo(policy == Policy.IMMEDIATE_RETRY ? 3 : 1);
        assertThat(result.remaining()).isEqualTo(46 - healthyConfirmed);
        assertThat(result.pgLookups()).isEqualTo(pgLookups);
        assertThat(result.recordedFailures()).isEqualTo(failures);
        assertThat(result.badLookups()).isEqualTo(badLookups);
        assertThat(result.distribution()).isEqualTo(Map.of(0, (long) healthyConfirmed));
    }

    private Outcome measure(Scene scene, int outageMinutes, Policy policy) throws Exception {
        var base = LookupSettingsFixture.LEARNING;
        var settings = new LookupResilienceSettings(base.budget(), base.attemptEstimate(),
            policy == Policy.IMMEDIATE_RETRY ? 3 : 1, base.firstWait(), base.waitMultiplier(),
            base.windowSize(), base.minimumCalls(), base.failureRateThreshold(), base.openWait(), base.halfOpenCalls());
        var provider = new ResilientPaymentProvider(adapter, settings, clock);
        var failures = new AtomicInteger();
        provider.lookupCircuitBreaker().getEventPublisher().onError(event -> failures.incrementAndGet());
        List<Tracked> backlog = new ArrayList<>();
        int count = scene == Scene.GLOBAL_OUTAGE ? 10 : 46;
        for (int i = 0; i < count; i++) {
            var created = adapter.create(new PaymentRequest("LP-BACKOFF-" + i, "TEST", "0000-0000-0000-0000", 7000));
            String key = created.transactions().getFirst().transactionKey();
            pg.changeStatus(key, "SUCCESS");
            backlog.add(new Tracked(key, 0, 0, i));
        }
        String badKey = scene == Scene.ONE_FAILING_TRADE ? backlog.getFirst().key() : null;
        int blocked = 0;
        int firstSweepConfirmed = 0;
        int firstSweepFailures = 0;
        Map<String, Integer> confirmedAt = new HashMap<>();
        for (int minute = 0; minute < MINUTES; minute++) {
            if (minute > 0) {
                clock.advance(Duration.ofMinutes(1));
            }
            for (Tracked tracked : List.copyOf(backlog)) {
                if (policy.backoff() && tracked.nextDueMinute() > minute) {
                    continue;
                }
                boolean fail = scene == Scene.GLOBAL_OUTAGE ? minute < outageMinutes : tracked.key().equals(badKey);
                if (fail) {
                    for (int i = 0; i < settings.maxAttempts(); i++) {
                        pg.replyNext(503, "");
                    }
                }
                int failuresBefore = failures.get();
                var observation = provider.findByTransactionKey(tracked.key());
                pg.clearQueuedFaults();
                backlog.remove(tracked);
                if (observation.kind() == PaymentObservation.Kind.OBSERVED && !observation.transactions().isEmpty()) {
                    confirmedAt.put(tracked.key(), minute);
                } else {
                    if (observation.unknownCause() == UnknownCause.CIRCUIT_OPEN) {
                        blocked++;
                    }
                    boolean increase = policy != Policy.BACKOFF_PG_FAILURE_ONLY || failures.get() > failuresBefore;
                    backlog.add(tracked.unconfirmedAt(minute, increase));
                }
            }
            backlog.sort((left, right) -> Integer.compare(left.order(), right.order()));
            if (minute == 0) {
                firstSweepConfirmed = confirmedAt.size();
                firstSweepFailures = failures.get();
            }
        }
        long badLookups = badKey == null ? 0 : pg.requests().stream()
            .filter(request -> "GET".equals(request.method()) && request.path().endsWith("/" + badKey)).count();
        Outcome result = new Outcome(Map.copyOf(confirmedAt), backlog.size(), blocked, pg.lookupCalls(), failures.get(),
            firstSweepConfirmed, firstSweepFailures, badLookups, provider.lookupCircuitBreaker().getState().name());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("scope", "Actual HTTP adapter/fake PG; in-memory scheduler and TestClock; not production batch/DB");
        record.put("scene", scene.name());
        record.put("outageMinutes", outageMinutes);
        record.put("policy", policy.name());
        record.put("simulatedMinutes", MINUTES);
        record.put("maxAttempts", settings.maxAttempts());
        record.put("openWaitSeconds", settings.openWait().toSeconds());
        record.put("confirmed", confirmedAt.size());
        record.put("confirmationMinutes", result.distribution());
        record.put("lastConfirmedMinute", result.lastConfirmedMinute());
        record.put("remaining", result.remaining());
        record.put("blocked", blocked);
        record.put("pgLookups", result.pgLookups());
        record.put("recordedFailures", result.recordedFailures());
        record.put("badLookups", badLookups);
        record.put("firstSweepConfirmed", firstSweepConfirmed);
        record.put("firstSweepFailures", firstSweepFailures);
        record.put("finalCircuit", result.finalCircuit());
        Path directory = Path.of(System.getProperty("experiment.repositoryRoot"), "docs/week5/measurements/batch-model-controls");
        Files.createDirectories(directory);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(
            scene.name().toLowerCase(java.util.Locale.ROOT) + "-" + outageMinutes + "-"
                + policy.name().toLowerCase(java.util.Locale.ROOT) + ".json").toFile(), record);
        System.out.println("batch-model " + record);
        return result;
    }

    private record Tracked(String key, int nextDueMinute, int failures, int order) {
        Tracked unconfirmedAt(int minute, boolean increase) {
            if (!increase) {
                return new Tracked(key, minute + 1, failures, order);
            }
            int nextFailures = failures + 1;
            long delay = Math.min(1L << (nextFailures - 1), MAX_BACKOFF_MINUTES);
            return new Tracked(key, minute + (int) delay, nextFailures, order);
        }
    }

    private record Outcome(Map<String, Integer> confirmedAt, int remaining, int blocked, int pgLookups,
                           int recordedFailures, int firstSweepConfirmed, int firstSweepFailures,
                           long badLookups, String finalCircuit) {
        int lastConfirmedMinute() { return confirmedAt.values().stream().mapToInt(Integer::intValue).max().orElse(-1); }
        Map<Integer, Long> distribution() {
            return confirmedAt.values().stream().collect(Collectors.groupingBy(value -> value, TreeMap::new, Collectors.counting()));
        }
    }

    enum Scene { GLOBAL_OUTAGE, ONE_FAILING_TRADE }

    enum Policy {
        IMMEDIATE_RETRY, SINGLE_ATTEMPT, BACKOFF_ALL_UNCONFIRMED, BACKOFF_PG_FAILURE_ONLY;
        boolean backoff() { return this == BACKOFF_ALL_UNCONFIRMED || this == BACKOFF_PG_FAILURE_ONLY; }
    }
}
