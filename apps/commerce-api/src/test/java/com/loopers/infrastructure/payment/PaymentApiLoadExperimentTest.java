package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.ExternalFakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 API·Tomcat·MySQL, 별도 PG JVM·k6 부하 생성기로 입력과 정합성을 함께 비교한다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "management.server.port=0", "server.tomcat.mbeanregistry.enabled=true",
    "spring.jpa.show-sql=false", "datasource.mysql-jpa.main.pool-name=api-load-pool"
})
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentApiLoadExperimentTest {

    private static final int STOCK = 10_000;
    private static final int PENDING_ORDERS = 128;
    private static final ProviderRouter ROUTER = new ProviderRouter();
    private static final ExternalFakePaymentServer PG = startPg();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @TestBean(name = "resilientPaymentProvider", methodName = "testProvider", enforceOverride = true)
    private PaymentProvider overriddenProvider;

    static PaymentProvider testProvider() {
        return ROUTER;
    }

    @LocalServerPort private int port;
    @LocalManagementPort private int managementPort;
    @Autowired private UserRegistrationService users;
    @Autowired private ProductFacade products;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository productRepository;
    @Autowired private OrderJpaRepository orders;
    @Autowired private PaymentAttemptJpaRepository attempts;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private MeterRegistry meters;

    private final List<TestPgPaymentAdapter> adapters = new ArrayList<>();
    private HttpClient client;
    private Long userId;
    private Long productId;
    private boolean isolated;

    @BeforeEach
    void prepare() throws Exception {
        String jdbcUrl = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertThat(System.getProperty("datasource.mysql-jpa.main.jdbc-url")).isNotBlank();
        assertThat(jdbcUrl).startsWith(System.getProperty("datasource.mysql-jpa.main.jdbc-url"));
        isolated = true;
        client = HttpClient.newHttpClient();
        PG.command("/up");
        PG.command("/reset");
        userId = users.register().getId();
        Long brand = brands.save(new BrandModel("API load", null)).getId();
        productId = products.create(brand, "API load product", 1000L, STOCK).id();
    }

    @AfterEach
    void clean() throws Exception {
        try {
            for (TestPgPaymentAdapter adapter : adapters) {
                adapter.close();
            }
            if (client != null) {
                client.close();
            }
        } finally {
            try {
                PG.command("/up");
                PG.command("/clearFaults");
            } finally {
                if (isolated) {
                    cleanUp.truncateAllTables();
                }
            }
        }
    }

    @AfterAll
    static void stopPg() throws Exception {
        PG.close();
    }

    @ParameterizedTest(name = "lookup {0}/s, policy={1}, repeat={2}")
    @CsvSource({"10,SHARED,1", "40,SHARED,1", "40,LIMITED,1", "40,SPLIT,1",
        "10,SHARED,2", "40,SHARED,2", "40,LIMITED,2", "40,SPLIT,2",
        "10,SHARED,3", "40,SHARED,3", "40,LIMITED,3", "40,SPLIT,3"})
    void comparesConcurrentApisAndVerifiesRecovery(int lookupRate, Policy policy, int repeat) throws Exception {
        measure(lookupRate, policy, repeat, null);
    }

    @ParameterizedTest(name = "combination={0}, repeat={1}")
    @CsvSource({"CURRENT,1", "LIMITED,1", "SHORT_TIMEOUT,1", "SLOW_CIRCUIT,1", "SINGLE_ATTEMPT,1",
        "CURRENT,2", "LIMITED,2", "SHORT_TIMEOUT,2", "SLOW_CIRCUIT,2", "SINGLE_ATTEMPT,2",
        "CURRENT,3", "LIMITED,3", "SHORT_TIMEOUT,3", "SINGLE_ATTEMPT,3"})
    void comparesProtectionThroughFaultAndRecovery(Combination combination, int repeat) throws Exception {
        measure(40, combination.limited ? Policy.LIMITED : Policy.SHARED, repeat, combination);
    }

    /** 45개 비교 중 이 조건은 회복 구간의 거절을 개별 재현할 수 있도록 분리한다. */
    @Test
    void verifiesSlowCircuitRecoveryWithLookupLimit() throws Exception {
        measure(40, Policy.LIMITED, 3, Combination.SLOW_CIRCUIT);
    }

    @ParameterizedTest(name = "scene={0}, combination={1}, repeat={2}")
    @CsvSource({"MIXED_LATENCY,CURRENT,1", "MIXED_LATENCY,LIMITED,1",
        "CONNECTION_RECOVERY,CURRENT,1",
        "RECOVERY_BURST,CURRENT,1", "RECOVERY_BURST,LIMITED,1",
        "MIXED_LATENCY,LIMITED,2", "MIXED_LATENCY,CURRENT,2",
        "CONNECTION_RECOVERY,LIMITED,2", "CONNECTION_RECOVERY,CURRENT,2",
        "RECOVERY_BURST,LIMITED,2", "RECOVERY_BURST,CURRENT,2",
        "MIXED_LATENCY,CURRENT,3", "MIXED_LATENCY,LIMITED,3",
        "CONNECTION_RECOVERY,CURRENT,3", "CONNECTION_RECOVERY,LIMITED,3",
        "RECOVERY_BURST,CURRENT,3", "RECOVERY_BURST,LIMITED,3"})
    void comparesAdditionalFailureScenes(Scene scene, Combination combination, int repeat) throws Exception {
        measure(40, combination.limited ? Policy.LIMITED : Policy.SHARED, repeat, combination, scene);
    }

    @Test
    void verifiesConnectionRecoveryWithLookupLimit() throws Exception {
        measure(40, Policy.LIMITED, 1, Combination.LIMITED, Scene.CONNECTION_RECOVERY);
    }

    @ParameterizedTest(name = "shared slow={0}, repeat={1}")
    @CsvSource({"CURRENT,1", "SHARED_SLOW_100,1", "SHARED_SLOW_300,1",
        "SHARED_SLOW_300,2", "SHARED_SLOW_100,2", "CURRENT,2",
        "CURRENT,3", "SHARED_SLOW_300,3", "SHARED_SLOW_100,3"})
    void comparesSlowThresholdsWithoutLookupLimit(Combination combination, int repeat) throws Exception {
        measure(40, Policy.SHARED, repeat, combination, Scene.PHASED, "review-slow-shared");
    }

    @ParameterizedTest(name = "without slow={0}, repeat={1}")
    @CsvSource({"SHORT_TIMEOUT,1", "SHORT_TIMEOUT_SINGLE,1", "SHORT_TIMEOUT_SINGLE,2",
        "SHORT_TIMEOUT,2", "SHORT_TIMEOUT,3", "SHORT_TIMEOUT_SINGLE,3"})
    void comparesAttemptsWithoutSlowCircuit(Combination combination, int repeat) throws Exception {
        measure(40, Policy.LIMITED, repeat, combination, Scene.PHASED, "review-attempts-without-slow");
    }

    @ParameterizedTest(name = "local retry={0}, repeat={1}")
    @CsvSource({"CURRENT,1", "SHARED_NO_POOL_RETRY,1", "SHARED_NO_POOL_RETRY,2",
        "CURRENT,2", "CURRENT,3", "SHARED_NO_POOL_RETRY,3"})
    void comparesLocalShortageRetry(Combination combination, int repeat) throws Exception {
        measure(40, Policy.SHARED, repeat, combination, Scene.STEADY, "review-pool-shortage-retry");
    }

    @ParameterizedTest(name = "mixed window={0}, repeat={1}")
    @CsvSource({"LIMITED,1", "LIMITED_WINDOW20,1", "LIMITED_WINDOW20,2",
        "LIMITED,2", "LIMITED,3", "LIMITED_WINDOW20,3"})
    void comparesWindowSizeWithMixedLatency(Combination combination, int repeat) throws Exception {
        measure(40, Policy.LIMITED, repeat, combination, Scene.MIXED_LATENCY, "review-mixed-window");
    }

    @ParameterizedTest(name = "OPEN 10s, scene={0}, combination={1}, repeat={2}")
    @CsvSource({"MIXED_LATENCY,CURRENT,1", "MIXED_LATENCY,LIMITED,1",
        "CONNECTION_RECOVERY,CURRENT,1", "CONNECTION_RECOVERY,LIMITED,1",
        "RECOVERY_BURST,CURRENT,1", "RECOVERY_BURST,LIMITED,1",
        "MIXED_LATENCY,LIMITED,2", "MIXED_LATENCY,CURRENT,2",
        "CONNECTION_RECOVERY,LIMITED,2", "CONNECTION_RECOVERY,CURRENT,2",
        "RECOVERY_BURST,LIMITED,2", "RECOVERY_BURST,CURRENT,2",
        "MIXED_LATENCY,CURRENT,3", "MIXED_LATENCY,LIMITED,3",
        "CONNECTION_RECOVERY,CURRENT,3", "CONNECTION_RECOVERY,LIMITED,3",
        "RECOVERY_BURST,CURRENT,3", "RECOVERY_BURST,LIMITED,3"})
    void comparesAdditionalScenesWithDefaultOpenWait(Scene scene, Combination combination, int repeat) throws Exception {
        measure(40, combination.limited ? Policy.LIMITED : Policy.SHARED, repeat, combination,
            scene, "review-open10", 25, LookupSettingsFixture.LEARNING.openWait());
    }

    @ParameterizedTest(name = "burst after probe, combination={0}, repeat={1}")
    @CsvSource({"CURRENT,1", "LIMITED,1", "LIMITED,2", "CURRENT,2", "CURRENT,3", "LIMITED,3"})
    void comparesBurstAfterDefaultOpenWait(Combination combination, int repeat) throws Exception {
        measure(40, combination.limited ? Policy.LIMITED : Policy.SHARED, repeat, combination,
            Scene.RECOVERY_BURST_AFTER_PROBE, "review-open10-after-probe", 25, LookupSettingsFixture.LEARNING.openWait());
    }

    private void measure(int lookupRate, Policy policy, int repeat, Combination combination) throws Exception {
        measure(lookupRate, policy, repeat, combination, combination == null ? Scene.STEADY : Scene.PHASED);
    }

    private void measure(int lookupRate, Policy policy, int repeat, Combination combination, Scene scene) throws Exception {
        measure(lookupRate, policy, repeat, combination, scene, null);
    }

    private void measure(int lookupRate, Policy policy, int repeat, Combination combination, Scene scene,
                         String comparisonGroup) throws Exception {
        Duration openWait = scene == Scene.STEADY ? LookupSettingsFixture.LEARNING.openWait() : Duration.ofSeconds(1);
        measure(lookupRate, policy, repeat, combination, scene, comparisonGroup, scene.seconds, openWait);
    }

    private void measure(int lookupRate, Policy policy, int repeat, Combination combination, Scene scene,
                         String comparisonGroup, int seconds, Duration openWait) throws Exception {
        boolean phased = scene != Scene.STEADY;
        int plannedLookups = lookupRate * seconds + (scene.hasBurst() ? 320 : 0);
        int creates = 4 * seconds;
        int delayMillis = phased ? 20 : 200;
        int lookupTimeout = combination != null ? combination.timeout : 500;
        boolean split = policy == Policy.SPLIT;
        Path root = Path.of(System.getProperty("experiment.repositoryRoot"));
        String folder = scene == Scene.STEADY ? "stage3-api" : scene == Scene.PHASED ? "stage4-api" : "additional-scenes";
        if (comparisonGroup != null) {
            folder = comparisonGroup;
        }
        Path directory = root.resolve("docs/week5/measurements/" + folder);
        Files.createDirectories(directory);
        String name = (phased || comparisonGroup != null ? combination.name().toLowerCase(java.util.Locale.ROOT)
            : "lookup-" + lookupRate + "-" + policy.name().toLowerCase(java.util.Locale.ROOT)) + "-" + repeat;
        if (scene != Scene.STEADY && scene != Scene.PHASED) {
            name = scene.name().toLowerCase(java.util.Locale.ROOT) + "-" + name;
        }
        Path resultFile = directory.resolve(name + "-result.json");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "Real Tomcat API and MySQL; separate PG JVM and Grafana k6 HTTP client");
        result.put("generator", "k6");
        result.put("scenario", name);
        result.put("scene", scene.name());
        result.put("plannedLookups", plannedLookups);
        result.put("pgDelayMs", delayMillis);
        result.put("policy", policy.name());
        result.put("seconds", seconds);
        result.put("lookupReadTimeoutMs", lookupTimeout);
        result.put("maxAttempts", combination != null ? combination.attempts : 3);
        result.put("attemptEstimateMs", LookupSettingsFixture.LEARNING.attemptEstimate().toMillis());
        result.put("slowCircuit", combination != null && combination.slowMillis > 0);
        result.put("slowThresholdMs", combination != null ? combination.slowMillis : 0);
        result.put("retryConnectionShortage", combination == null || combination.retryLocal);
        result.put("rates", Map.of("lookup", lookupRate, "create", 4, "product", 20));
        result.put("environment", Map.of("javaVersion", System.getProperty("java.version"),
            "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"),
            "availableProcessors", Runtime.getRuntime().availableProcessors(),
            "jacocoEnabled", ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(arg -> arg.contains("jacoco")),
            "hikariMaximum", meters.get("hikaricp.connections.max").gauge().value(),
            "tomcatMaximumThreads", meters.get("tomcat.threads.config.max").gauge().value(),
            "pgSendsCallbacks", false, "sameHost", true));
        result.put("completed", false);
        try {
            TestPgPaymentAdapter lookup = adapter(split ? 3 : 25, split ? 3 : 5, name + "-lookup", lookupTimeout);
            TestPgPaymentAdapter create = split ? adapter(2, 2, name + "-create", lookupTimeout) : lookup;
            var settings = combination != null ? experimentSettings(combination, openWait) : LookupSettingsFixture.LEARNING;
            result.put("circuitWindowSize", settings.windowSize());
            result.put("circuitMinimumCalls", settings.minimumCalls());
            result.put("circuitOpenWaitMs", settings.openWait().toMillis());
            var resilient = provider(new TimedPaymentProvider(lookup, meters), settings,
                combination != null ? combination.slowMillis : 0, combination == null || combination.retryLocal);
            var circuitRejected = new AtomicInteger();
            resilient.lookupCircuitBreaker().getEventPublisher().onCallNotPermitted(event -> circuitRejected.incrementAndGet());
            List<Map<String, Object>> transitions = new CopyOnWriteArrayList<>();
            resilient.lookupCircuitBreaker().getEventPublisher().onStateTransition(event -> transitions.add(Map.of(
                "epochMillis", System.currentTimeMillis(),
                "transition", event.getStateTransition().name())));
            PaymentProvider queries = policy == Policy.LIMITED
                ? new LimitedLookupPaymentProvider(resilient, 3, meters, name) : resilient;
            ROUTER.delegate = new RoutedProvider(new TimedPaymentProvider(create, meters), queries);
            List<Long> pending = new ArrayList<>();
            List<Long> newOrders = new ArrayList<>();
            for (int i = 0; i < PENDING_ORDERS; i++) {
                Long id = draft();
                Api confirmed = api("POST", "/api/v1/orders/" + id + "/confirm", confirmBody(id));
                assertThat(confirmed.status).isEqualTo(202);
                assertThat(confirmed.body.path("data").path("paymentStatus").asText()).isEqualTo("PENDING");
                pending.add(id);
            }
            for (int i = 0; i < creates; i++) {
                newOrders.add(draft());
            }
            PG.stats();
            // 각 API 경로의 최초 로딩을 측정 부하와 구분한다. 생성은 앞의 128건으로 준비했다.
            for (int i = 0; i < 30; i++) {
                assertThat(api("POST", "/api/v1/orders/" + pending.get(i) + "/payment/reconcile", null).status).isEqualTo(202);
                assertThat(api("GET", "/api/v1/products/" + productId, null).status).isEqualTo(200);
            }
            JsonNode beforePg = PG.stats();
            result.put("pgBeforeLoad", beforePg);
            result.put("attemptTimersBeforeLoad", attemptTimers());
            long attemptsBefore = lookupAttempts(null);
            long unavailableBefore = lookupAttempts("CONNECTION_UNAVAILABLE");
            long temporaryBefore = lookupAttempts("TEMPORARY_FAILURE");
            String beforeMetrics = prometheus();
            assertThat(beforeMetrics).contains("commerce_pg_call_seconds_count", "hikaricp_connections_active", "tomcat_threads_busy");
            Path configFile = directory.resolve(name + "-config.json");
            Path clientFile = directory.resolve(name + "-client.json");
            Map<String, Object> config = new LinkedHashMap<>(Map.of("baseUrl", "http://127.0.0.1:" + port,
                "userId", userId, "productId", productId, "pendingOrderIds", pending, "newOrderIds", newOrders,
                "seconds", seconds, "rates", Map.of("lookup", lookupRate, "create", 4, "product", 20)));
            if (phased) {
                config.put("controlUrl", PG.controlUrl());
                config.put("phases", phases(scene));
            }
            if (scene.hasBurst()) {
                config.put("rateWindows", Map.of("lookup", List.of(Map.of(
                    "startSeconds", scene.burstStart(), "endSeconds", scene.burstStart() + 4, "rate", 120))));
            }
            result.put("rateWindows", config.getOrDefault("rateWindows", Map.of()));
            MAPPER.writeValue(configFile.toFile(), config);
            PG.command("/delay?ms=" + delayMillis);
            RuntimeSample sample = new RuntimeSample(meters, name);
            try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
                sampler.scheduleAtFixedRate(sample::sample, 0, 20, TimeUnit.MILLISECONDS);
                Path rawFile = directory.resolve(name + "-k6-calls.jsonl");
                Path k6Summary = directory.resolve(name + "-k6-summary.json");
                Files.writeString(rawFile, "");
                List<String> command = List.of("k6", "run", "--no-usage-report", "--quiet", "--log-format", "raw",
                    "--log-output", "file=" + rawFile, "-e", "CONFIG_FILE=" + configFile,
                    "-e", "SUMMARY_FILE=" + k6Summary, root.resolve("scripts/week5-payment-api.k6.js").toString());
                result.put("k6Command", command);
                Process generator = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(directory.resolve(name + "-client.log").toFile()).start();
                try {
                    assertThat(generator.waitFor(Math.max(40, seconds + 25), TimeUnit.SECONDS)).isTrue();
                    assertThat(generator.exitValue()).isZero();
                    Process reporter = new ProcessBuilder("python3", root.resolve("scripts/summarize-week5-k6.py").toString(),
                        "--config", configFile.toString(), "--raw", rawFile.toString(), "--k6-summary", k6Summary.toString(),
                        "--output", clientFile.toString()).redirectErrorStream(true)
                        .redirectOutput(directory.resolve(name + "-report.log").toFile()).start();
                    try {
                        assertThat(reporter.waitFor(10, TimeUnit.SECONDS)).isTrue();
                        assertThat(reporter.exitValue()).isZero();
                    } finally {
                        if (reporter.isAlive()) {
                            reporter.destroyForcibly();
                            reporter.waitFor(5, TimeUnit.SECONDS);
                        }
                    }
                } finally {
                    if (generator.isAlive()) {
                        generator.destroyForcibly();
                        generator.waitFor(5, TimeUnit.SECONDS);
                    }
                    sampler.shutdownNow();
                }
            }
            JsonNode load = MAPPER.readTree(clientFile.toFile());
            JsonNode pg = PG.stats();
            assertThat(load.path("generator").asText()).isEqualTo("k6");
            assertThat(load.path("droppedIterations").asInt()).isZero();
            result.put("k6", load.path("k6Summary"));
            result.put("droppedIterations", load.path("droppedIterations"));
            result.put("boundaryIterationsIgnored", load.path("boundaryIterationsIgnored"));
            result.put("client", load.path("summary"));
            result.put("phases", load.path("phases"));
            result.put("phaseChanges", load.path("phaseChanges"));
            result.put("circuitTransitions", transitions.stream().map(event -> Map.of(
                "elapsedMs", ((Number) event.get("epochMillis")).doubleValue() - load.path("startedEpochMillis").asDouble(),
                "transition", event.get("transition"))).toList());
            result.put("peakClientInFlight", load.path("peakClientInFlight"));
            result.put("runtime", sample.values);
            result.put("pgBeforeRecovery", pg);
            result.put("attemptTimersAfterLoad", attemptTimers());
            result.put("circuit", resilient.lookupCircuitBreaker().getState().name());
            verifyHttp(load, plannedLookups, seconds);
            JsonNode createStatuses = load.path("summary").path("create").path("paymentStatuses");
            int accepted = createStatuses.path("PENDING").asInt();
            assertThat(accepted + createStatuses.path("FAILED").asInt() + createStatuses.path("UNKNOWN").asInt()).isEqualTo(creates);
            if (!phased) {
                assertThat(createStatuses.path("UNKNOWN").asInt()).isZero();
            }
            if (!phased && (policy != Policy.SHARED || lookupRate == 10)) {
                assertThat(accepted).isEqualTo(creates);
            }
            if (!phased) {
                assertThat(pg.path("createCalls").asInt()).isEqualTo(PENDING_ORDERS + accepted);
                assertThat(pg.path("transactionCount").asInt()).isEqualTo(pg.path("createCalls").asInt());
            }
            assertThat(pg.path("distinctCreateOrders").asInt()).isEqualTo(pg.path("createCalls").asInt());
            int checked = load.path("summary").path("lookup").path("checkedTrue").asInt();
            int received = pg.path("lookupCalls").asInt() - beforePg.path("lookupCalls").asInt();
            long adapterAttempts = lookupAttempts(null) - attemptsBefore;
            long localAcquisitionFailures = lookupAttempts("CONNECTION_UNAVAILABLE") - unavailableBefore;
            if (scene.hasConnectionOutage()) {
                assertThat(adapterAttempts).isGreaterThan(received + localAcquisitionFailures);
            } else {
                assertThat(adapterAttempts).isEqualTo(received + localAcquisitionFailures);
            }
            result.put("unreceivedLookupAttempts", adapterAttempts - received - localAcquisitionFailures);
            result.put("temporaryFailureAttempts", lookupAttempts("TEMPORARY_FAILURE") - temporaryBefore);
            result.put("adapterLookupAttempts", adapterAttempts);
            result.put("localAcquisitionFailures", localAcquisitionFailures);
            result.put("circuitRejected", circuitRejected.get());
            if (phased) {
                assertThat(received).isGreaterThanOrEqualTo(checked);
            } else {
                assertThat(received).isEqualTo(checked);
            }
            assertThat(resilient.lookupCircuitBreaker().getState().name()).isEqualTo("CLOSED");
            double rejected = meters.find("commerce.pg.lookup.rejected").tag("limiter", name).counters()
                .stream().mapToDouble(counter -> counter.count()).sum();
            result.put("concurrencyRejected", rejected);
            if (!phased && policy == Policy.LIMITED) {
                assertThat(rejected).isPositive().isEqualTo(load.path("summary").path("lookup").path("checkedFalse").asDouble());
            }
            result.put("afterLoad", inventory(creates));
            JsonNode pgTransactions = PG.transactions();
            result.put("pgTransactionsBeforeRecovery", pgTransactions);
            verifyStoredResults(pgTransactions, pg, false);
            Files.writeString(directory.resolve(name + "-prometheus.txt"), prometheus());

            PG.command("/clearFaults");
            PG.command("/complete?status=SUCCESS");
            Set<String> createdOrders = new java.util.HashSet<>();
            pgTransactions.forEach(entry -> createdOrders.add(entry.path("orderId").asText()));
            List<Map<String, Object>> recoveryResponses = new ArrayList<>();
            for (var attempt : attempts.findAll()) {
                boolean terminal = createdOrders.contains(attempt.getProviderOrderId()) || attempt.getStatus() == PaymentAttemptStatus.FAILED;
                Api response = api("POST", "/api/v1/orders/" + attempt.getOrderId() + "/payment/reconcile", null);
                assertThat(response.status).isEqualTo(terminal ? 200 : 202);
                recoveryResponses.add(Map.of("orderId", attempt.getOrderId(), "httpStatus", response.status,
                    "paymentStatus", response.body.path("data").path("paymentStatus").asText(),
                    "checked", response.body.path("data").path("checked").asBoolean()));
            }
            result.put("recoveryResponses", recoveryResponses);
            Map<String, Object> recovered = inventory(creates);
            verifyStoredResults(pgTransactions, pg, true);
            if (!phased) {
                assertThat(recovered.get("reserved")).isEqualTo(0);
            }
            assertThat(recovered.get("consumed")).isEqualTo(pg.path("transactionCount").asInt());
            JsonNode after = PG.stats();
            assertThat(after.path("createCalls").asInt()).isEqualTo(pg.path("createCalls").asInt());
            result.put("afterRecovery", recovered);
            result.put("pgAfterRecovery", after);
            result.put("remainingPaymentStatuses", attempts.findAll().stream().collect(Collectors.groupingBy(
                attempt -> attempt.getStatus().name(), Collectors.counting())));
            result.put("dataConsistencyVerified", true);
            if (phased) {
                List<JsonNode> finalLookups = new ArrayList<>();
                load.path("calls").forEach(call -> {
                    if (call.path("group").asText().equals("lookup")
                        && call.path("startOffsetMs").asDouble() >= (seconds - 1) * 1000) {
                        finalLookups.add(call);
                    }
                });
                result.put("recoveryAcceptance", Map.of("started", finalLookups.size(),
                    "checked", finalLookups.stream().filter(call -> call.path("checked").asBoolean()).count(),
                    "requiredMinimumStarted", 35, "requiresEveryLookupChecked", true));
                // 보호 후보의 회복 기준이 실패해도 그 전에 주문별 복구·재고 대조를 끝낸다. 기대값은 유지한다.
                if (scene == Scene.PHASED) {
                    verifyPhases(load, transitions);
                } else {
                    verifyAdditionalScene(scene, load, transitions, pg, seconds, plannedLookups);
                }
            }
            result.put("completed", true);
            System.out.printf("API-LOAD %s create=%s lookup=%s product=%s%n", name,
                load.path("summary").path("create"), load.path("summary").path("lookup"), load.path("summary").path("product"));
        } finally {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);
        }
    }

    private static LookupResilienceSettings experimentSettings(Combination combination, Duration openWait) {
        var base = LookupSettingsFixture.LEARNING;
        return new LookupResilienceSettings(base.budget(), base.attemptEstimate(),
            combination.attempts, base.firstWait(), base.waitMultiplier(), combination.window, base.minimumCalls(),
            base.failureRateThreshold(), openWait, base.halfOpenCalls());
    }

    private static ResilientPaymentProvider provider(PaymentProvider delegate, LookupResilienceSettings settings,
                                                     int slowMillis, boolean retryLocal) {
        var current = new ResilientPaymentProvider(delegate, settings, Clock.systemUTC());
        var circuit = current.lookupCircuitBreaker();
        if (slowMillis > 0) {
            circuit = CircuitBreaker.of("phaseSlow", CircuitBreakerConfig.from(circuit.getCircuitBreakerConfig())
                .slowCallDurationThreshold(Duration.ofMillis(slowMillis)).slowCallRateThreshold(50).build());
        }
        return retryLocal ? new ResilientPaymentProvider(delegate, settings, circuit)
            : new NoPoolRetryPaymentProvider(delegate, settings, circuit);
    }

    private static List<Map<String, Object>> phases() {
        return List.of(
            Map.of("name", "NORMAL", "offsetSeconds", 0, "commands", List.of("/clearFaults", "/delay?ms=20")),
            Map.of("name", "SLOW", "offsetSeconds", 3, "commands", List.of("/delay?ms=200")),
            Map.of("name", "TIMEOUT", "offsetSeconds", 6, "commands", List.of("/delay?ms=800")),
            Map.of("name", "SERVER_ERROR", "offsetSeconds", 9, "commands", List.of("/delay?ms=20", "/fail?status=503&count=10000")),
            Map.of("name", "RECOVERY", "offsetSeconds", 12, "commands", List.of("/clearFaults", "/delay?ms=20")));
    }

    private static List<Map<String, Object>> phases(Scene scene) {
        return switch (scene) {
            case PHASED -> phases();
            case MIXED_LATENCY -> List.of(
                phase("NORMAL", 0, "/clearFaults", "/delay?ms=20"),
                phase("MIXED_LATENCY", 2, "/lookupDelay?every=5&ms=800"),
                phase("RECOVERY", 10, "/clearFaults", "/delay?ms=20"));
            case CONNECTION_RECOVERY -> List.of(
                phase("NORMAL", 0, "/clearFaults", "/delay?ms=20"), phase("PG_DOWN", 2, "/down"),
                phase("RECOVERY", 6, "/up", "/delay?ms=20"));
            case RECOVERY_BURST -> List.of(
                phase("NORMAL", 0, "/clearFaults", "/delay?ms=20"), phase("PG_DOWN", 2, "/down"),
                phase("RECOVERY_BURST", 6, "/delay?ms=200", "/up"),
                phase("RECOVERY", 10, "/delay?ms=20"));
            case RECOVERY_BURST_AFTER_PROBE -> List.of(
                phase("NORMAL", 0, "/clearFaults", "/delay?ms=20"), phase("PG_DOWN", 2, "/down"),
                phase("RECOVERY_WAIT", 6, "/delay?ms=200", "/up"),
                phase("RECOVERY_BURST", 12, "/delay?ms=200"),
                phase("RECOVERY", 16, "/delay?ms=20"));
            default -> List.of();
        };
    }

    private static Map<String, Object> phase(String name, int offset, String... commands) {
        return Map.of("name", name, "offsetSeconds", offset, "commands", List.of(commands));
    }

    private static void verifyAdditionalScene(Scene scene, JsonNode load, List<Map<String, Object>> transitions, JsonNode pg,
                                               int seconds, int plannedLookups) {
        int phases = scene == Scene.RECOVERY_BURST_AFTER_PROBE ? 5 : scene == Scene.RECOVERY_BURST ? 4 : 3;
        assertThat(load.path("phaseChanges").size()).isEqualTo(phases);
        if (scene.hasConnectionOutage()) {
            assertThat(transitions.stream().map(event -> event.get("transition")))
                .contains("CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED");
        } else {
            assertThat(pg.path("patternLookupCalls").asInt()).isPositive();
            assertThat(pg.path("delayedLookupCalls").asInt()).isEqualTo(pg.path("patternLookupCalls").asInt() / 5);
        }
        List<JsonNode> finalLookups = new ArrayList<>();
        int plannedDuringBurst = 0;
        for (JsonNode call : load.path("calls")) {
            if (!call.path("group").asText().equals("lookup")) {
                continue;
            }
            if (call.path("startOffsetMs").asDouble() >= (seconds - 1) * 1000) {
                finalLookups.add(call);
            }
            double planned = call.path("plannedOffsetMs").asDouble();
            if (planned >= scene.burstStart() * 1000 && planned < (scene.burstStart() + 4) * 1000) {
                plannedDuringBurst++;
            }
        }
        if (scene.hasBurst()) {
            assertThat(load.path("scheduledCounts").path("lookup").asInt()).isEqualTo(plannedLookups);
            assertThat(plannedDuringBurst).isEqualTo(480);
        }
        assertThat(finalLookups).hasSizeGreaterThanOrEqualTo(35);
        assertThat(finalLookups).allSatisfy(call -> assertThat(call.path("checked").asBoolean()).isTrue());
    }

    private static void verifyPhases(JsonNode load, List<Map<String, Object>> transitions) {
        assertThat(load.path("phaseChanges").size()).isEqualTo(5);
        assertThat(transitions.stream().map(event -> event.get("transition")))
            .contains("CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED");
        List<JsonNode> finalLookups = new ArrayList<>();
        for (JsonNode call : load.path("calls")) {
            if (call.path("group").asText().equals("lookup") && call.path("startOffsetMs").asDouble() >= 15_000) {
                finalLookups.add(call);
            }
        }
        assertThat(finalLookups).hasSizeGreaterThanOrEqualTo(35);
        assertThat(finalLookups).allSatisfy(call -> assertThat(call.path("checked").asBoolean()).isTrue());
    }

    private void verifyStoredResults(JsonNode pgTransactions, JsonNode pg, boolean recovered) {
        Map<String, JsonNode> actual = new LinkedHashMap<>();
        for (JsonNode entry : pgTransactions) {
            assertThat(actual.put(entry.path("orderId").asText(), entry)).isNull();
        }
        Set<String> receivedCreates = new java.util.HashSet<>();
        pg.path("createOrderIds").forEach(id -> receivedCreates.add(id.asText()));
        assertThat(actual).hasSize(pg.path("transactionCount").asInt());
        for (var attempt : attempts.findAll()) {
            JsonNode entry = actual.get(attempt.getProviderOrderId());
            if (entry != null) {
                assertThat(entry.path("amount").asLong()).isEqualTo(attempt.getAmount());
                if (recovered) {
                    assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
                    assertThat(attempt.getTransactionKey()).isEqualTo(entry.path("transactionKey").asText());
                } else {
                    assertThat(attempt.getStatus()).isIn(PaymentAttemptStatus.PENDING, PaymentAttemptStatus.UNKNOWN);
                }
            } else if (attempt.getStatus() == PaymentAttemptStatus.FAILED) {
                assertThat(receivedCreates).doesNotContain(attempt.getProviderOrderId());
            } else {
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            }
        }
    }

    private TestPgPaymentAdapter adapter(int total, int perRoute, String poolName, int lookupTimeout) {
        var pool = PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(total).setMaxConnPerRoute(perRoute).build();
        var adapter = new TestPgPaymentAdapter(PG.baseUrl(), "http://127.0.0.1:" + port + "/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(lookupTimeout),
            HttpClients.custom().setConnectionManager(new TimedPgConnectionManager(pool, meters, poolName)));
        adapters.add(adapter);
        return adapter;
    }

    private void verifyHttp(JsonNode load, int plannedLookups, int seconds) {
        for (String group : List.of("lookup", "create", "product")) {
            JsonNode summary = load.path("summary").path(group);
            int expected = switch (group) { case "lookup" -> plannedLookups; case "create" -> 4 * seconds; default -> 20 * seconds; };
            assertThat(summary.path("count").asInt()).isEqualTo(expected);
            assertThat(summary.path("transportErrors").asInt()).isZero();
            if (group.equals("lookup")) {
                assertThat(summary.path("checkedTrue").asInt() + summary.path("checkedFalse").asInt()).isEqualTo(expected);
            }
            int ok = summary.path("httpStatuses").path("200").asInt();
            int pending = summary.path("httpStatuses").path("202").asInt();
            assertThat(ok + pending).isEqualTo(expected);
            if (group.equals("product")) {
                assertThat(ok).isEqualTo(expected);
            }
        }
    }

    private Map<String, Object> inventory(int creates) {
        return transaction.execute(status -> {
            var allOrders = orders.findAll();
            var allAttempts = attempts.findAll();
            assertThat(allOrders).hasSize(PENDING_ORDERS + creates);
            assertThat(allAttempts).hasSize(allOrders.size());
            Map<Long, PaymentAttemptStatus> payment = new LinkedHashMap<>();
            allAttempts.forEach(attempt -> payment.put(attempt.getOrderId(), attempt.getStatus()));
            int reserved = 0;
            int consumed = 0;
            int released = 0;
            for (var order : allOrders) {
                assertThat(order.getItems()).hasSize(1);
                int quantity = order.getItems().getFirst().getQuantity();
                assertThat(quantity).isEqualTo(1);
                if (order.getReservationStatus() == ReservationStatus.RESERVED) {
                    reserved += quantity;
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                    assertThat(payment.get(order.getId())).isIn(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.UNKNOWN, PaymentAttemptStatus.PENDING);
                } else if (order.getReservationStatus() == ReservationStatus.CONSUMED) {
                    consumed += quantity;
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(payment.get(order.getId())).isEqualTo(PaymentAttemptStatus.SUCCESS);
                    assertThat(order.getPaidAmount()).isEqualTo(order.getTotalAmount());
                } else {
                    released += quantity;
                    assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.RELEASED);
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
                    assertThat(payment.get(order.getId())).isEqualTo(PaymentAttemptStatus.FAILED);
                }
            }
            int available = productRepository.findById(productId).orElseThrow().getStockQuantity();
            assertThat(available + reserved + consumed).isEqualTo(STOCK);
            return Map.of("available", available, "reserved", reserved, "consumed", consumed, "released", released,
                "orders", allOrders.size(), "attempts", allAttempts.size());
        });
    }

    private Long draft() throws Exception {
        Api created = api("POST", "/api/v1/orders", MAPPER.writeValueAsString(Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)))));
        assertThat(created.status).isEqualTo(201);
        return created.body.path("data").path("id").asLong();
    }

    private String confirmBody(Long id) throws Exception {
        return MAPPER.writeValueAsString(Map.of("paymentAttemptKey", "api-load-" + id, "cardType", "TEST", "cardNo", "0000-0000-0000-0000"));
    }

    private Api api(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
            .header("X-USER-ID", userId.toString()).header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Api(response.statusCode(), MAPPER.readTree(response.body()));
    }

    private String prometheus() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private Map<String, Object> attemptTimers() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Timer timer : meters.find("commerce.pg.call").timers()) {
            result.put(timer.getId().getTag("operation") + ":" + timer.getId().getTag("result"),
                Map.of("count", timer.count(), "totalMs", timer.totalTime(TimeUnit.MILLISECONDS)));
        }
        return result;
    }

    private long lookupAttempts(String result) {
        return meters.find("commerce.pg.call").timers().stream()
            .filter(timer -> timer.getId().getTag("operation").startsWith("lookup_"))
            .filter(timer -> result == null || result.equals(timer.getId().getTag("result")))
            .mapToLong(Timer::count).sum();
    }

    private static ExternalFakePaymentServer startPg() {
        try {
            return new ExternalFakePaymentServer();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private enum Scene {
        STEADY(10), PHASED(16), MIXED_LATENCY(14), CONNECTION_RECOVERY(14), RECOVERY_BURST(14), RECOVERY_BURST_AFTER_PROBE(25);

        private final int seconds;
        Scene(int seconds) { this.seconds = seconds; }
        boolean hasConnectionOutage() { return this == CONNECTION_RECOVERY || hasBurst(); }
        boolean hasBurst() { return this == RECOVERY_BURST || this == RECOVERY_BURST_AFTER_PROBE; }
        int burstStart() { return this == RECOVERY_BURST_AFTER_PROBE ? 12 : 6; }
    }

    private enum Policy { SHARED, LIMITED, SPLIT }

    private enum Combination {
        CURRENT(false, 500, 0, 3), LIMITED(true, 500, 0, 3), SHORT_TIMEOUT(true, 250, 0, 3),
        SLOW_CIRCUIT(true, 250, 100, 3), SINGLE_ATTEMPT(true, 250, 100, 1),
        SHARED_SLOW_100(false, 500, 100, 3), SHARED_SLOW_300(false, 500, 300, 3),
        SHORT_TIMEOUT_SINGLE(true, 250, 0, 1),
        SHARED_NO_POOL_RETRY(false, 500, 0, 3, false, 4),
        LIMITED_WINDOW20(true, 500, 0, 3, true, 20);

        private final boolean limited;
        private final int timeout;
        private final int slowMillis;
        private final int attempts;
        private final boolean retryLocal;
        private final int window;

        Combination(boolean limited, int timeout, int slowMillis, int attempts) {
            this(limited, timeout, slowMillis, attempts, true, 4);
        }

        Combination(boolean limited, int timeout, int slowMillis, int attempts, boolean retryLocal, int window) {
            this.limited = limited;
            this.timeout = timeout;
            this.slowMillis = slowMillis;
            this.attempts = attempts;
            this.retryLocal = retryLocal;
            this.window = window;
        }
    }

    private record Api(int status, JsonNode body) {}

    private static final class ProviderRouter implements PaymentProvider {
        private volatile PaymentProvider delegate;
        @Override public PaymentObservation create(PaymentRequest request) { return delegate.create(request); }
        @Override public PaymentObservation findByTransactionKey(String key) { return delegate.findByTransactionKey(key); }
        @Override public PaymentObservation findByOrderId(String id) { return delegate.findByOrderId(id); }
    }

    private record RoutedProvider(PaymentProvider create, PaymentProvider lookup) implements PaymentProvider {
        @Override public PaymentObservation create(PaymentRequest request) { return create.create(request); }
        @Override public PaymentObservation findByTransactionKey(String key) { return lookup.findByTransactionKey(key); }
        @Override public PaymentObservation findByOrderId(String id) { return lookup.findByOrderId(id); }
    }

    private static final class RuntimeSample {
        private final MeterRegistry meters;
        private final String prefix;
        private final Map<String, Double> values = new LinkedHashMap<>();

        private RuntimeSample(MeterRegistry meters, String prefix) {
            this.meters = meters;
            this.prefix = prefix;
        }

        private void sample() {
            for (String name : List.of("tomcat.threads.busy", "hikaricp.connections.active", "hikaricp.connections.pending")) {
                double value = meters.find(name).gauges().stream().mapToDouble(gauge -> gauge.value()).max().orElse(-1);
                values.merge(name, value, Math::max);
            }
            for (var gauge : meters.find("httpcomponents.httpclient.pool.total.pending").gauges()) {
                String pool = gauge.getId().getTag("httpclient");
                if (pool != null && pool.startsWith(prefix)) {
                    values.merge(pool + ".pending", gauge.value(), Math::max);
                }
            }
            for (var gauge : meters.find("httpcomponents.httpclient.pool.total.connections").tag("state", "leased").gauges()) {
                String pool = gauge.getId().getTag("httpclient");
                if (pool != null && pool.startsWith(prefix)) {
                    values.merge(pool + ".leased", gauge.value(), Math::max);
                }
            }
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
                values.merge("processCpuLoad", extended.getProcessCpuLoad(), Math::max);
            }
        }
    }
}
