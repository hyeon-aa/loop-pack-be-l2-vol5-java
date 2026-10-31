package com.loopers.infrastructure.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 풀 획득 요청부터 endpoint 반환까지를 잰다. 새 연결 수립과 PG 응답 대기는 이 타이머 밖이다. */
final class TimedPgConnectionManager implements HttpClientConnectionManager {

    private final PoolingHttpClientConnectionManager delegate;
    private final MeterRegistry registry;
    private final Timer acquired;
    private final Timer timeout;
    private final Timer error;
    private final Timer cancelled;

    TimedPgConnectionManager(PoolingHttpClientConnectionManager delegate, MeterRegistry registry, String poolName) {
        this.delegate = delegate;
        this.registry = registry;
        new PoolingHttpClientConnectionManagerMetricsBinder(delegate, poolName).bindTo(registry);
        acquired = timer(poolName, "acquired");
        timeout = timer(poolName, "timeout");
        error = timer(poolName, "error");
        cancelled = timer(poolName, "cancelled");
    }

    @Override
    public LeaseRequest lease(String id, HttpRoute route, Timeout requestTimeout, Object state) {
        Timer.Sample sample = Timer.start(registry);
        LeaseRequest request;
        try {
            request = delegate.lease(id, route, requestTimeout, state);
        } catch (RuntimeException | Error exception) {
            sample.stop(error);
            throw exception;
        }
        AtomicBoolean recorded = new AtomicBoolean();
        return new LeaseRequest() {
            @Override
            public ConnectionEndpoint get(Timeout wait) throws InterruptedException, ExecutionException, TimeoutException {
                Timer result = error;
                try {
                    ConnectionEndpoint endpoint = request.get(wait);
                    result = acquired;
                    return endpoint;
                } catch (TimeoutException exception) {
                    result = timeout;
                    throw exception;
                } catch (ExecutionException exception) {
                    result = exception.getCause() instanceof TimeoutException ? timeout : error;
                    throw exception;
                } finally {
                    recordOnce(sample, result, recorded);
                }
            }

            @Override
            public boolean cancel() {
                boolean accepted = request.cancel();
                if (accepted) {
                    recordOnce(sample, cancelled, recorded);
                }
                return accepted;
            }
        };
    }

    @Override
    public void release(ConnectionEndpoint endpoint, Object newState, TimeValue validDuration) {
        delegate.release(endpoint, newState, validDuration);
    }

    @Override
    public void connect(ConnectionEndpoint endpoint, TimeValue connectTimeout, HttpContext context) throws IOException {
        delegate.connect(endpoint, connectTimeout, context);
    }

    @Override
    public void upgrade(ConnectionEndpoint endpoint, HttpContext context) throws IOException {
        delegate.upgrade(endpoint, context);
    }

    @Override
    public void close(CloseMode closeMode) {
        delegate.close(closeMode);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private Timer timer(String poolName, String result) {
        return Timer.builder("commerce.pg.pool.acquire").description("Pool acquisition before connecting to PG")
            .tags("pool", poolName, "result", result).publishPercentileHistogram().register(registry);
    }

    private static void recordOnce(Timer.Sample sample, Timer timer, AtomicBoolean recorded) {
        if (recorded.compareAndSet(false, true)) {
            sample.stop(timer);
        }
    }
}
