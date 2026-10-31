package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 시도 1회·간격 1→2→4→8분을 고정하고 격리 기준만 비교한다.
 * PG 전체 장애의 정상 거래와, 조회 0건 뒤 나중에 보이는 거래를 잘못 제외하는지 확인한다.
 * 4분은 반례를 만드는 조건이며 실제 PG 반영 지연의 상한이 아니다. 예약·알림·수동 처리의 통합 검증은 아니다.
 */
@Tag("experiment")
class PaymentLookupIsolationExperimentTest {

    private static final int MINUTES = 15;
    private static final int ISOLATE_AFTER = 3;
    private static final long MAX_BACKOFF_MINUTES = 8;

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private TestClock clock;
    private int created;

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

    // 0건만 세어도 0·1·3분에 0건인 거래가 4분부터 보이면, 격리가 그 거래를 놓칠 것으로 예상한다.
    @ParameterizedTest(name = "{0} / {1}: confirmed={2}, isolated={3}, targetAt={4}, targetGET={5}")
    @CsvSource({
        "PG_OUTAGE_3,      NONE,              10, 0, -1, 0",
        "PG_OUTAGE_3,      ABSENCE_AND_503,   10, 0, -1, 0",
        "PG_OUTAGE_3,      ABSENCE_ONLY,      10, 0, -1, 0",
        "PG_OUTAGE,        NONE,              10, 0, -1, 0",
        "PG_OUTAGE,        ABSENCE_AND_503,    8, 2, -1, 0",
        "PG_OUTAGE,        ABSENCE_ONLY,      10, 0, -1, 0",
        "MISSING_FOREVER,  NONE,               3, 0, -1, 4",
        "MISSING_FOREVER,  ABSENCE_AND_503,    3, 1, -1, 3",
        "MISSING_FOREVER,  ABSENCE_ONLY,      3, 1, -1, 3",
        "VISIBLE_AFTER_4,  NONE,               4, 0,  7, 4",
        "VISIBLE_AFTER_4,  ABSENCE_AND_503,    3, 1, -1, 3",
        "VISIBLE_AFTER_4,  ABSENCE_ONLY,      3, 1, -1, 3"
    })
    void checksIsolationAgainstPgOutageAndLateVisibility(Scene scene, IsolationRule rule, int expectedConfirmed,
                                                        int expectedIsolated, int expectedTargetMinute,
                                                        int expectedTargetLookups) throws Exception {
        LookupResilienceSettings base = LookupSettingsFixture.LEARNING;
        LookupResilienceSettings settings = new LookupResilienceSettings(base.budget(), base.attemptEstimate(),
            1, base.firstWait(), base.waitMultiplier(), base.windowSize(), base.minimumCalls(),
            base.failureRateThreshold(), base.openWait(), base.halfOpenCalls());
        ResilientPaymentProvider provider = new ResilientPaymentProvider(adapter, settings, clock);

        List<Tracked> backlog = new ArrayList<>();
        String targetKey = null;
        if (scene == Scene.MISSING_FOREVER) {
            targetKey = "TEST-TX-MISSING";
            backlog.add(new Tracked(targetKey, 0, 0, 0, created++));
        } else if (scene == Scene.VISIBLE_AFTER_4) {
            Tracked target = create("late");
            targetKey = target.key();
            backlog.add(target);
        }
        boolean fullOutage = scene == Scene.PG_OUTAGE || scene == Scene.PG_OUTAGE_3;
        int outageMinutes = scene == Scene.PG_OUTAGE_3 ? 3 : 4;
        int otherTransactions = fullOutage ? 10 : 3;
        for (int i = 0; i < otherTransactions; i++) {
            backlog.add(create("other-" + i));
        }
        Map<String, Integer> confirmedAt = new HashMap<>();
        Set<String> isolated = new HashSet<>();

        for (int minute = 0; minute < MINUTES; minute++) {
            if (minute > 0) {
                clock.advance(Duration.ofMinutes(1));
            }
            for (Tracked tracked : List.copyOf(backlog)) {
                if (tracked.nextDueMinute() > minute) {
                    continue;
                }
                if (fullOutage && minute < outageMinutes) {
                    pg.replyNext(503, "");
                } else if (scene == Scene.VISIBLE_AFTER_4 && tracked.key().equals(targetKey) && minute < 4) {
                    // PG 거래는 있지만 이 기간의 조회에서는 보이지 않는 조건을 404로 만든다.
                    pg.replyNext(404, "");
                }
                PaymentObservation result = provider.findByTransactionKey(tracked.key());
                pg.clearQueuedFaults();
                backlog.remove(tracked);
                if (result.kind() == PaymentObservation.Kind.OBSERVED && !result.transactions().isEmpty()) {
                    confirmedAt.put(tracked.key(), minute);
                    continue;
                }
                Tracked next = tracked.unconfirmedAt(minute, countsForIsolation(result, rule));
                if (rule != IsolationRule.NONE && next.isolationCount() >= ISOLATE_AFTER) {
                    isolated.add(next.key());
                } else {
                    backlog.add(next);
                }
            }
            backlog.sort((left, right) -> Integer.compare(left.order(), right.order()));
        }

        String target = targetKey;
        int targetMinute = confirmedAt.getOrDefault(target, -1);
        long targetLookups = target == null ? 0 : pg.requests().stream()
            .filter(request -> "GET".equals(request.method()) && request.path().endsWith("/" + target)).count();
        System.out.printf("isolation,%s,%s,confirmed=%d,isolated=%d,targetConfirmedMinute=%d,targetLookups=%d%n",
            scene, rule, confirmedAt.size(), isolated.size(), targetMinute, targetLookups);

        Path directory = Path.of(System.getProperty("experiment.repositoryRoot"), "docs/week5/measurements/isolation-controls");
        Files.createDirectories(directory);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("scope", "Actual HTTP adapter/fake PG; in-memory batch/TestClock; no alarm/manual workflow validation");
        record.put("scene", scene.name());
        record.put("rule", rule.name());
        record.put("backoffRule", "ALL_UNCONFIRMED");
        record.put("isolateAfter", ISOLATE_AFTER);
        record.put("halfOpenProbes", settings.halfOpenCalls());
        record.put("outageMinutes", fullOutage ? outageMinutes : 0);
        record.put("confirmed", confirmedAt.size());
        record.put("isolated", isolated.size());
        record.put("targetConfirmedMinute", targetMinute);
        record.put("targetLookups", targetLookups);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(
            scene.name().toLowerCase(java.util.Locale.ROOT) + "-" + rule.name().toLowerCase(java.util.Locale.ROOT) + ".json").toFile(), record);

        assertThat(confirmedAt).hasSize(expectedConfirmed);
        assertThat(isolated).hasSize(expectedIsolated);
        assertThat(targetMinute).isEqualTo(expectedTargetMinute);
        assertThat(targetLookups).isEqualTo(expectedTargetLookups);
        if (scene == Scene.VISIBLE_AFTER_4) {
            // 비교 집계 밖의 직접 조회로, 놓친 거래가 PG에서는 이제 SUCCESS로 보임을 확인한다.
            PaymentObservation visible = adapter.findByTransactionKey(targetKey);
            assertThat(visible.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
            assertThat(visible.transactions()).hasSize(1);
            assertThat(visible.transactions().getFirst().status()).isEqualTo(PaymentTransaction.Status.SUCCESS);
            assertThat(isolated.contains(targetKey)).isEqualTo(expectedIsolated == 1);
        }
    }

    private static boolean countsForIsolation(PaymentObservation result, IsolationRule rule) {
        if (rule == IsolationRule.NONE) {
            return false;
        }
        if (result.kind() == PaymentObservation.Kind.OBSERVED) {
            return result.transactions().isEmpty();
        }
        return rule == IsolationRule.ABSENCE_AND_503 && result.unknownCause() == UnknownCause.TEMPORARY_FAILURE;
    }

    private Tracked create(String label) {
        PaymentObservation observation = adapter.create(new PaymentRequest(
            "LP-ISOLATION-" + label, "TEST", "0000-0000-0000-0000", 7000));
        String key = observation.transactions().getFirst().transactionKey();
        pg.changeStatus(key, "SUCCESS");
        return new Tracked(key, 0, 0, 0, created++);
    }

    private record Tracked(String key, int nextDueMinute, int retries, int isolationCount, int order) {
        Tracked unconfirmedAt(int minute, boolean counted) {
            int nextRetries = retries + 1;
            long backoff = Math.min(1L << (nextRetries - 1), MAX_BACKOFF_MINUTES);
            return new Tracked(key, minute + (int) backoff, nextRetries, counted ? isolationCount + 1 : isolationCount, order);
        }
    }

    enum Scene { PG_OUTAGE, PG_OUTAGE_3, MISSING_FOREVER, VISIBLE_AFTER_4 }

    enum IsolationRule { NONE, ABSENCE_AND_503, ABSENCE_ONLY }
}
