package com.loopers.support.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** API 부하 실험에서만 소유·종료하는 별도 PG JVM. 포트는 OS가 고른다. */
public final class ExternalFakePaymentServer implements AutoCloseable {

    private final Process process;
    private final HttpClient control = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String controlUrl;

    public ExternalFakePaymentServer() throws Exception {
        String classpath = System.getProperty("experiment.runtimeClasspath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("experimentTest의 runtimeClasspath가 필요합니다.");
        }
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, FakePaymentServerMain.class.getName(), "0", "0")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String line;
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var executor = Executors.newSingleThreadExecutor();
        try {
            line = executor.submit(reader::readLine).get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            process.destroyForcibly();
            control.close();
            throw failure;
        } finally {
            executor.shutdownNow();
            reader.close();
        }
        if (line == null || !line.startsWith("fake PG: ")) {
            process.destroyForcibly();
            control.close();
            throw new IllegalStateException("PG 시작 실패: " + line);
        }
        String[] addresses = line.substring("fake PG: ".length()).split(", control: ");
        baseUrl = addresses[0];
        controlUrl = addresses[1];
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String controlUrl() {
        return controlUrl;
    }

    public JsonNode transactions() throws Exception {
        return send("/transactions", false);
    }

    public JsonNode command(String path) throws Exception {
        return send(path, true);
    }

    public JsonNode stats() throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode stats = send("/stats", false);
            if (stats.path("createCalls").asLong() == stats.path("create").path("count").asLong()
                && stats.path("lookupCalls").asLong() == stats.path("lookup").path("count").asLong()) {
                return stats;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("PG 요청이 끝나지 않았습니다.");
    }

    private JsonNode send(String path, boolean post) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(controlUrl + path)).timeout(Duration.ofSeconds(5));
        if (post) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        var response = control.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("PG 제어 실패: " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    @Override
    public void close() throws Exception {
        try {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("PG 프로세스가 종료되지 않았습니다.");
                }
            }
        } finally {
            control.close();
        }
    }
}
