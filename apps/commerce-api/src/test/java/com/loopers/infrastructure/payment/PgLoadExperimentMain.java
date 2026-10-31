package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 별도 JVM의 가짜 PG를 쓰는 HTTP 경계 실험. 공급한 제어 URL의 상태를 조건마다 초기화한다. */
public final class PgLoadExperimentMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALLBACK = "http://127.0.0.1:8082/api/v1/payments/callback";

    private PgLoadExperimentMain() {}

    public static void main(String[] args) throws Exception {
        String pgUrl = args[0];
        String controlUrl = args[1];
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        int repeats = Integer.parseInt(args[3]);
        String selection = args[4];
        check(repeats > 0, "repeats must be positive");
        check(List.of("all", "causes", "slow", "protection").contains(selection), "unknown experiment set");
        List<Map<String, Object>> rows = new ArrayList<>();
        try (HttpClient control = HttpClient.newHttpClient()) {
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (Scenario scenario : Scenario.values()) {
                    if (!selection.equals("all") && !scenario.group.equals(selection)) {
                        continue;
                    }
                    Map<String, Object> row = run(control, pgUrl, controlUrl, scenario, repeat);
                    rows.add(row);
                    Files.createDirectories(output.getParent());
                    MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                        "scope", "Real HTTP PG boundary; separate fake PG JVM; no order API, DB or Tomcat load",
                        "java", System.getProperty("java.version"), "rows", rows));
                    System.out.printf("%s repeat=%d lookup=%s create=%s%n", scenario, repeat,
                        row.get("lookup"), row.get("create"));
                    @SuppressWarnings("unchecked")
                    Map<String, Boolean> checks = (Map<String, Boolean>) row.get("checks");
                    check(checks.values().stream().allMatch(Boolean::booleanValue), "failed checks: " + checks);
                }
            }
        }
    }

    private static Map<String, Object> run(HttpClient control, String pgUrl, String controlUrl,
                                           Scenario scenario, int repeat) throws Exception {
        control(control, controlUrl + "/reset", true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Pool> pools = new ArrayList<>();
        List<TestPgPaymentAdapter> adapters = new ArrayList<>();
        try {
            TestPgPaymentAdapter lookupAdapter = adapter(pgUrl, scenario.lookupConnections, "lookup", registry, pools);
            adapters.add(lookupAdapter);
            TestPgPaymentAdapter createAdapter = scenario.split
                ? adapter(pgUrl, 2, "create", registry, pools) : lookupAdapter;
            if (scenario.split) {
                adapters.add(createAdapter);
            }
            // 준비 POST·워밍업은 raw adapter로 실행하고 풀 타이머의 초기 합계·건수는 뺀다.
            PaymentObservation seed = createAdapter.create(request("seed"));
            check(seed.kind() == PaymentObservation.Kind.OBSERVED, "seed create failed");
            String warmupKey = seed.transactions().getFirst().transactionKey();
            for (int i = 0; i < 3; i++) {
                check(lookupAdapter.findByTransactionKey(warmupKey).kind() == PaymentObservation.Kind.OBSERVED, "warmup failed");
            }
            settledStats(control, controlUrl);
            control(control, controlUrl + "/reset", true);
            seed = createAdapter.create(request("seed"));
            check(seed.kind() == PaymentObservation.Kind.OBSERVED, "seed recreate failed");
            String key = seed.transactions().getFirst().transactionKey();
            Map<String, Long> initialAcquires = new LinkedHashMap<>();
            Map<String, Double> initialAcquireMillis = new LinkedHashMap<>();
            for (Pool pool : pools) {
                initialAcquires.put(pool.name, timerCount(registry, "commerce.pg.pool.acquire", "pool", pool.name));
                initialAcquireMillis.put(pool.name, registry.get("commerce.pg.pool.acquire")
                    .tags("pool", pool.name, "result", "acquired").timer().totalTime(TimeUnit.MILLISECONDS));
            }
            long initialPgLookups = settledStats(control, controlUrl).path("lookupCalls").asLong();
            PaymentProvider lookup = new TimedPaymentProvider(lookupAdapter, registry);
            ConcurrentLinkedQueue<String> transitions = new ConcurrentLinkedQueue<>();
            if (scenario.group.equals("protection")) {
                lookup = new ResilientPaymentProvider(lookup, LookupSettingsFixture.LEARNING, Clock.systemUTC());
            } else if (scenario.group.equals("slow")) {
                var base = LookupSettingsFixture.LEARNING;
                var once = new LookupResilienceSettings(base.budget(), base.attemptEstimate(), 1, base.firstWait(),
                    base.waitMultiplier(), base.windowSize(), base.minimumCalls(), base.failureRateThreshold(), base.openWait(), base.halfOpenCalls());
                var defaults = new ResilientPaymentProvider(lookup, once, Clock.systemUTC());
                if (scenario.slowCall) {
                    CircuitBreaker candidate = CircuitBreaker.of("slowCandidate", CircuitBreakerConfig.from(defaults.lookupCircuitBreaker().getCircuitBreakerConfig())
                        .slowCallDurationThreshold(Duration.ofMillis(100)).slowCallRateThreshold(50).build());
                    lookup = new ResilientPaymentProvider(lookup, once, candidate);
                } else {
                    lookup = defaults;
                }
                ((ResilientPaymentProvider) lookup).lookupCircuitBreaker().getEventPublisher()
                    .onStateTransition(event -> transitions.add(event.getStateTransition().toString()));
            }
            PaymentProvider create = new TimedPaymentProvider(createAdapter, registry);
            Semaphore permits = scenario.bulkhead ? new Semaphore(3) : null;
            control(control, controlUrl + "/delay?ms=" + scenario.delayMillis, true);
            List<Planned> schedule = schedule(scenario);
            List<Result> results = new ArrayList<>();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peakActive = new AtomicInteger();
            long started = System.nanoTime();
            try (var sampler = Executors.newSingleThreadScheduledExecutor();
                 var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                sampler.scheduleAtFixedRate(() -> pools.forEach(Pool::sample), 0, 5, TimeUnit.MILLISECONDS);
                List<Future<Result>> futures = new ArrayList<>();
                for (Planned planned : schedule) {
                    long wait = started + planned.offsetNanos - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    PaymentProvider provider = planned.create ? create : lookup;
                    futures.add(workers.submit(() -> execute(provider, key, planned, started, permits, active, peakActive)));
                }
                for (Future<Result> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }
                sampler.shutdownNow();
            }
            double drainedMillis = millis(System.nanoTime() - started);
            JsonNode pg = settledStats(control, controlUrl);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", scenario.name());
            row.put("repeat", repeat);
            row.put("settings", Map.of("durationSeconds", scenario.seconds, "lookupRate", scenario.lookupRate,
                "createRate", scenario.createRate, "pgDelayMs", scenario.delayMillis,
                "lookupConnections", scenario.lookupConnections, "separateCreateConnections", scenario.split ? 2 : 0,
                "lookupRetries", scenario.group.equals("protection") ? 3 : 1,
                "requiredLookupConnectionsEstimate", scenario.lookupRate * scenario.delayMillis / 1000.0));
            row.put("drainedMillis", drainedMillis);
            row.put("peakClientInFlight", peakActive.get());
            row.put("lookup", summarize(results, false, scenario.seconds));
            row.put("create", summarize(results, true, scenario.seconds));
            Map<String, Object> poolMetrics = new LinkedHashMap<>();
            for (Pool pool : pools) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("peakPending", pool.peakPending.get());
                values.put("peakLeased", pool.peakLeased.get());
                values.put("acquire", poolTimers(registry, pool.name, initialAcquires.get(pool.name), initialAcquireMillis.get(pool.name)));
                values.put("setupAcquireCount", initialAcquires.get(pool.name));
                poolMetrics.put(pool.name, values);
            }
            row.put("pools", poolMetrics);
            row.put("adapterLookupAttempts", timers(registry, "commerce.pg.call", "operation", "lookup_transaction"));
            row.put("adapterCreateAttempts", timers(registry, "commerce.pg.call", "operation", "create"));
            row.put("pg", pg);
            row.put("setupPgLookups", initialPgLookups);
            if (scenario.group.equals("slow")) {
                row.put("slowCallCandidate", Map.of("enabled", scenario.slowCall, "durationMs", 100, "rateThreshold", 50,
                    "windowSize", 4, "minimumCalls", 4));
            }
            long lookupObserved = count(results, false, "OBSERVED");
            long lookupUnknown = count(results, false, "CONNECTION_UNAVAILABLE");
            long rejected = count(results, false, "REJECTED_BY_LIMIT");
            long circuitDenied = count(results, false, "CIRCUIT_OPEN");
            long createObserved = count(results, true, "OBSERVED");
            long createNotSent = count(results, true, "NOT_SENT");
            long poolTimeouts = timerResultCount(registry, "commerce.pg.pool.acquire", "timeout");
            long lookupAttempts = timerCount(registry, "commerce.pg.call", "operation", "lookup_transaction");
            long createAttempts = timerCount(registry, "commerce.pg.call", "operation", "create");
            long localLookupAttempts = timerResultCount(registry, "commerce.pg.call", "CONNECTION_UNAVAILABLE");
            Map<String, Boolean> checks = new LinkedHashMap<>();
            checks.put("allScheduledCallsAccountedFor", results.size() == schedule.size());
            checks.put("lookupHasOnlyExpectedOutcomes", lookupObserved + lookupUnknown + rejected + circuitDenied == scenario.lookupRate * scenario.seconds);
            checks.put("createHasOnlyObservedOrProvenNotSent", createObserved + createNotSent == scenario.createRate * scenario.seconds);
            checks.put("oneAttemptPerCreate", createAttempts == scenario.createRate * scenario.seconds);
            checks.put("pgLookupMatchesActualSentAttempts", pg.path("lookupCalls").asLong() - initialPgLookups == lookupAttempts - localLookupAttempts);
            checks.put("pgCreateMatchesObservedPlusSeed", pg.path("createCalls").asLong() == createObserved + 1);
            checks.put("localTimeoutsMatchClassifiedAttempts", poolTimeouts == localLookupAttempts + createNotSent);
            checks.put("noDuplicateCreateOrders", pg.path("distinctCreateOrders").asLong() == pg.path("createCalls").asLong());
            checks.put("transactionsMatchReceivedCreates", pg.path("transactionCount").asLong() == pg.path("createCalls").asLong());
            if (lookup instanceof ResilientPaymentProvider resilient) {
                CircuitBreaker circuit = resilient.lookupCircuitBreaker();
                row.put("circuit", circuit.getState().name());
                row.put("circuitMetrics", Map.of("buffered", circuit.getMetrics().getNumberOfBufferedCalls(),
                    "failed", circuit.getMetrics().getNumberOfFailedCalls(), "slow", circuit.getMetrics().getNumberOfSlowCalls(),
                    "slowRate", circuit.getMetrics().getSlowCallRate(), "deniedLogicalCalls", circuitDenied));
                row.put("circuitTransitions", List.copyOf(transitions));
                if (scenario.group.equals("slow")) {
                    boolean expectedOpen = scenario.slowCall && (scenario.delayMillis > 100 || scenario.lookupConnections == 1);
                    checks.put("expectedSlowCallDecision", circuit.getState() == (expectedOpen ? CircuitBreaker.State.OPEN : CircuitBreaker.State.CLOSED));
                    checks.put("noPgFailuresInSlowSuccessComparison", circuit.getMetrics().getNumberOfFailedCalls() == 0);
                    if (expectedOpen) {
                        checks.put("actuallyBlockedCalls", circuitDenied > 0 && !transitions.isEmpty());
                    }
                    if (scenario.delayMillis < 100) {
                        checks.put("fastPgReallyStayedBelowCandidateThreshold", pg.path("lookup").path("maxMs").asLong() < 100);
                    }
                } else {
                    checks.put("localShortageDoesNotOpenPgCircuit", circuit.getState() == CircuitBreaker.State.CLOSED);
                }
            }
            if (scenario.split || scenario.bulkhead) {
                checks.put("protectedCreatesAllObserved", createObserved == scenario.createRate * scenario.seconds);
            }
            row.put("checks", checks);
            return row;
        } finally {
            for (TestPgPaymentAdapter adapter : adapters) {
                adapter.close();
            }
            registry.close();
        }
    }

    private static TestPgPaymentAdapter adapter(String url, int connections, String name, SimpleMeterRegistry registry,
                                                List<Pool> pools) {
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(connections).setMaxConnPerRoute(connections).build();
        pools.add(new Pool(name, manager));
        return new TestPgPaymentAdapter(url, CALLBACK, Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500),
            HttpClients.custom().setConnectionManager(new TimedPgConnectionManager(manager, registry, name)));
    }

    private static Result execute(PaymentProvider provider, String key, Planned planned, long start, Semaphore permits,
                                  AtomicInteger active, AtomicInteger peakActive) {
        long entered = System.nanoTime();
        peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        boolean acquired = planned.create || permits == null || permits.tryAcquire();
        try {
            if (!acquired) {
                return new Result(false, "REJECTED_BY_LIMIT", millis(System.nanoTime() - entered),
                    millis(entered - start - planned.offsetNanos), millis(entered - start));
            }
            PaymentObservation observation = planned.create ? provider.create(request("new-" + planned.number))
                : provider.findByTransactionKey(key);
            if (observation.kind() == PaymentObservation.Kind.OBSERVED) {
                check(observation.transactions().size() == 1
                    && observation.transactions().getFirst().status() == PaymentTransaction.Status.PENDING, "unexpected PG business result");
            }
            String result = observation.kind() == PaymentObservation.Kind.UNKNOWN ? observation.unknownCause().name() : observation.kind().name();
            return new Result(planned.create, result, millis(System.nanoTime() - entered),
                millis(entered - start - planned.offsetNanos), millis(entered - start));
        } finally {
            if (acquired && !planned.create && permits != null) {
                permits.release();
            }
            active.decrementAndGet();
        }
    }

    private static List<Planned> schedule(Scenario scenario) {
        List<Planned> schedule = new ArrayList<>();
        for (int i = 0; i < scenario.lookupRate * scenario.seconds; i++) {
            schedule.add(new Planned(false, i, i * 1_000_000_000L / scenario.lookupRate));
        }
        for (int i = 0; i < scenario.createRate * scenario.seconds; i++) {
            schedule.add(new Planned(true, i, (2L * i + 1) * 1_000_000_000L / (2L * scenario.createRate)));
        }
        schedule.sort(Comparator.comparingLong(Planned::offsetNanos));
        return schedule;
    }

    private static Map<String, Object> summarize(List<Result> results, boolean create, int seconds) {
        List<Result> selected = results.stream().filter(r -> r.create == create).toList();
        Map<String, Long> outcomes = new LinkedHashMap<>();
        selected.forEach(r -> outcomes.merge(r.result, 1L, Long::sum));
        List<Double> times = selected.stream().map(Result::elapsedMillis).sorted().toList();
        List<Double> lags = selected.stream().map(Result::launchLagMillis).sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", selected.size());
        summary.put("outcomes", outcomes);
        summary.put("p50Ms", percentile(times, .5));
        summary.put("p99Ms", percentile(times, .99));
        summary.put("maxStartLagMs", percentile(lags, 1));
        summary.put("observedAbove100Ms", selected.stream().filter(r -> r.result.equals("OBSERVED") && r.elapsedMillis > 100).count());
        long withinWindow = selected.stream().filter(r -> r.startedOffsetMillis < seconds * 1000.0).count();
        summary.put("startedWithinScheduleWindow", withinWindow);
        summary.put("actualStartsPerSecond", withinWindow / (double) seconds);
        return summary;
    }

    private static Map<String, Object> poolTimers(SimpleMeterRegistry registry, String pool, long initialCount, double initialMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Timer timer : registry.find("commerce.pg.pool.acquire").tag("pool", pool).timers()) {
            boolean acquired = "acquired".equals(timer.getId().getTag("result"));
            long count = timer.count() - (acquired ? initialCount : 0);
            double total = timer.totalTime(TimeUnit.MILLISECONDS) - (acquired ? initialMillis : 0);
            result.put(timer.getId().getTag("result"), Map.of("count", count, "meanMs", count == 0 ? 0 : total / count));
        }
        return result;
    }

    private static Map<String, Object> timers(SimpleMeterRegistry registry, String name, String tag, String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Timer timer : registry.find(name).tag(tag, value).timers()) {
            result.put(timer.getId().getTag("result"), Map.of("count", timer.count(),
                "meanMs", timer.mean(TimeUnit.MILLISECONDS), "maxMs", timer.max(TimeUnit.MILLISECONDS)));
        }
        return result;
    }

    private static long timerCount(SimpleMeterRegistry registry, String name, String tag, String value) {
        return registry.find(name).tag(tag, value).timers().stream().mapToLong(Timer::count).sum();
    }

    private static long timerResultCount(SimpleMeterRegistry registry, String name, String value) {
        return timerCount(registry, name, "result", value);
    }

    private static long count(List<Result> results, boolean create, String outcome) {
        return results.stream().filter(r -> r.create == create && r.result.equals(outcome)).count();
    }

    private static JsonNode settledStats(HttpClient client, String url) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode stats = control(client, url + "/stats", false);
            if (stats.path("lookupCalls").asLong() == stats.path("lookup").path("count").asLong()
                && stats.path("createCalls").asLong() == stats.path("create").path("count").asLong()) {
                return stats;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("fake PG handlers did not drain");
    }

    private static JsonNode control(HttpClient client, String url, boolean post) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
        if (post) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "control failed: " + response.statusCode());
        return MAPPER.readTree(response.body());
    }

    private static PaymentRequest request(String suffix) {
        return new PaymentRequest("LP-LOAD-" + suffix, "TEST", "0000-0000-0000-0000", 7000);
    }

    private static double percentile(List<Double> sorted, double rank) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(rank * sorted.size()) - 1));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Planned(boolean create, int number, long offsetNanos) {}
    private record Result(boolean create, String result, double elapsedMillis, double launchLagMillis, double startedOffsetMillis) {}

    private static final class Pool {
        private final String name;
        private final PoolingHttpClientConnectionManager manager;
        private final AtomicInteger peakPending = new AtomicInteger();
        private final AtomicInteger peakLeased = new AtomicInteger();

        private Pool(String name, PoolingHttpClientConnectionManager manager) {
            this.name = name;
            this.manager = manager;
        }

        private void sample() {
            var stats = manager.getTotalStats();
            peakPending.accumulateAndGet(stats.getPending(), Math::max);
            peakLeased.accumulateAndGet(stats.getLeased(), Math::max);
        }
    }

    private enum Scenario {
        FAST_PG_BUSY_POOL("causes", 20, 1, 100, 0, 2, false, false),
        SLOW_PG_FREE_POOL("causes", 200, 32, 100, 0, 2, false, false),
        SLOW_PG_BUSY_POOL("causes", 200, 1, 100, 0, 2, false, false),
        FAST_FREE_FAILURE_ONLY("slow", 20, 32, 100, 0, 2, false, false),
        FAST_FREE_SLOW_CALL("slow", 20, 32, 100, 0, 2, false, false, true),
        SLOW_FREE_FAILURE_ONLY("slow", 200, 32, 100, 0, 2, false, false),
        SLOW_FREE_SLOW_CALL("slow", 200, 32, 100, 0, 2, false, false, true),
        FAST_BUSY_FAILURE_ONLY("slow", 20, 1, 100, 0, 2, false, false),
        FAST_BUSY_SLOW_CALL("slow", 20, 1, 100, 0, 2, false, false, true),
        SHARED_POOL("protection", 200, 5, 60, 6, 4, false, false),
        LOOKUP_LIMIT("protection", 200, 5, 60, 6, 4, false, true),
        SEPARATE_POOLS("protection", 200, 3, 60, 6, 4, true, false);

        private final String group;
        private final int delayMillis;
        private final int lookupConnections;
        private final int lookupRate;
        private final int createRate;
        private final int seconds;
        private final boolean split;
        private final boolean bulkhead;
        private final boolean slowCall;

        Scenario(String group, int delayMillis, int lookupConnections, int lookupRate, int createRate, int seconds,
                 boolean split, boolean bulkhead) {
            this(group, delayMillis, lookupConnections, lookupRate, createRate, seconds, split, bulkhead, false);
        }

        Scenario(String group, int delayMillis, int lookupConnections, int lookupRate, int createRate, int seconds,
                 boolean split, boolean bulkhead, boolean slowCall) {
            this.group = group;
            this.delayMillis = delayMillis;
            this.lookupConnections = lookupConnections;
            this.lookupRate = lookupRate;
            this.createRate = createRate;
            this.seconds = seconds;
            this.split = split;
            this.bulkhead = bulkhead;
            this.slowCall = slowCall;
        }
    }
}
