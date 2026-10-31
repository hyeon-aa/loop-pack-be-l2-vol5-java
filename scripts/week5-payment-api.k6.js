import http from 'k6/http';
import execution from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const config = JSON.parse(open(__ENV.CONFIG_FILE));
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(config.baseUrl)) {
  throw new Error('This test requires the isolated local API server');
}
if (config.phases?.length && !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(config.controlUrl)) {
  throw new Error('PG control must be the isolated local fake PG');
}
const contexts = {};
const scenarios = {};
for (const [group, baseRate] of Object.entries(config.rates)) {
  const windows = config.rateWindows?.[group] || [];
  const cuts = [...new Set([0, config.seconds, ...windows.flatMap(w => [w.startSeconds, w.endSeconds])])].sort((a, b) => a - b);
  let offset = 0;
  for (let i = 0; i < cuts.length - 1; i++) {
    const begin = cuts[i], end = cuts[i + 1];
    const rate = windows.find(w => w.startSeconds <= begin && begin < w.endSeconds)?.rate ?? baseRate;
    if (!Number.isInteger(rate) || rate < 1 || end <= begin) throw new Error('Invalid arrival-rate segment');
    const name = `${group}_${i}`;
    contexts[name] = { group, begin, rate, offset, count: (end - begin) * rate };
    scenarios[name] = {
      executor: 'constant-arrival-rate', exec: 'apiRequest', rate, timeUnit: '1s',
      duration: `${end - begin}s`, startTime: `${begin}s`, gracefulStop: '15s',
      preAllocatedVUs: group === 'lookup' ? Math.max(16, rate * 2) : Math.max(8, rate),
    };
    offset += (end - begin) * rate;
  }
}
scenarios.pg_control = { executor: 'shared-iterations', exec: 'controlPg', vus: 1, iterations: 1, maxDuration: `${config.seconds + 5}s` };

export const options = {
  scenarios, maxRedirects: 0,
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: { dropped_iterations: ['count==0'], checks: ['rate==1'] },
};
const elapsed = {
  lookup: new Trend('api_lookup_elapsed', true),
  create: new Trend('api_create_elapsed', true),
  product: new Trend('api_product_elapsed', true),
};
const boundaryIterations = new Counter('api_boundary_iterations_ignored');
const counts = {
  lookup: new Counter('api_lookup_calls'), create: new Counter('api_create_calls'), product: new Counter('api_product_calls'),
};
function now() { return execution.instance.currentTestRunDuration; }
function record(value) { console.log(JSON.stringify(value)); }

export function controlPg() {
  record({ kind: 'origin', startedEpochMillis: Date.now() - now() });
  for (const phase of config.phases || []) {
    const delay = phase.offsetSeconds - now() / 1000;
    if (delay > 0) sleep(delay);
    const change = { kind: 'phase', name: phase.name, plannedOffsetMs: phase.offsetSeconds * 1000, startedOffsetMs: now() };
    for (const path of phase.commands) {
      const response = http.post(config.controlUrl + path, null, { timeout: '5s', redirects: 0, tags: { name: 'pg_control' } });
      const valid = check(response, { 'PG control returned 200': r => r.status === 200 });
      if (!valid) throw new Error(`PG control failed: ${path}, status=${response.status}`);
    }
    change.appliedOffsetMs = now();
    record(change);
  }
}

export function apiRequest() {
  const context = contexts[execution.scenario.name];
  const iteration = execution.scenario.iterationInTest;
  // A timer tick at a segment boundary must not send an extra API request or reuse a create order.
  if (iteration >= context.count) { boundaryIterations.add(1); return; }
  const index = context.offset + iteration;
  const group = context.group;
  const started = Date.now();
  const startOffsetMs = now();
  const plannedOffsetMs = (context.begin + iteration / context.rate) * 1000;
  const result = { kind: 'call', group, startOffsetMs, plannedOffsetMs, startLagMs: startOffsetMs - plannedOffsetMs };
  let method = 'POST', path, body = null;
  if (group === 'create') {
    result.orderId = config.newOrderIds[index];
    if (result.orderId === undefined) throw new Error('No prepared order for create iteration');
    path = `/api/v1/orders/${result.orderId}/confirm`;
    body = JSON.stringify({ paymentAttemptKey: `api-load-${result.orderId}`, cardType: 'TEST', cardNo: '0000-0000-0000-0000' });
  } else if (group === 'lookup') {
    result.orderId = config.pendingOrderIds[index % config.pendingOrderIds.length];
    path = `/api/v1/orders/${result.orderId}/payment/reconcile`;
  } else {
    result.orderId = null;
    method = 'GET'; path = `/api/v1/products/${config.productId}`;
  }
  try {
    const response = http.request(method, config.baseUrl + path, body, {
      headers: { 'X-USER-ID': String(config.userId), 'Content-Type': 'application/json' },
      timeout: '15s', redirects: 0, tags: { name: `api_${group}` },
    });
    result.httpStatus = response.status;
    if (response.status === 0) result.transportError = response.error || 'HTTP transport failed';
    const payload = response.status === 0 ? {} : response.json();
    const data = payload.data || {};
    result.paymentStatus = data.paymentStatus ?? null;
    result.checked = data.checked ?? null;
    result.errorCode = payload.meta?.errorCode ?? null;
    check(response, { 'Expected API HTTP status': r => group === 'product' ? r.status === 200 : r.status === 200 || r.status === 202 });
  } catch (error) {
    result.httpStatus = result.httpStatus || 0;
    result.transportError = String(error);
    check(false, { 'API request and JSON succeeded': value => value });
  }
  result.elapsedMs = Date.now() - started;
  elapsed[group].add(result.elapsedMs);
  counts[group].add(1);
  record(result);
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 2) + '\n' };
}
