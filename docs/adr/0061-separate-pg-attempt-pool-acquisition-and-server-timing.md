# ADR 0061: PG 호출 시도·풀 획득·서버 처리 시간을 따로 관찰한다

Status: Accepted
Extended by: [ADR 0062](0062-classify-pool-future-deadline-as-local-shortage.md) — 획득 future 기한 초과도 timeout으로 기록하며 예외 전파 보존
Extends: [ADR 0031](0031-pg-lookup-retry-circuit-breaker-and-fallback.md), [ADR 0060](0060-exclude-lookup-connection-shortage-from-circuit.md)

## Context

조회 연결 부족은 회로 집계에서 제외하지만 retry는 유지한다. 이후 풀 분리·느린 호출 기준을 비교하려면, 우리 풀에서 기다린 시간과 PG가 응답을 처리한 시간을 구별해야 한다. 기존 retry 전체 소요 시간만으로는 어느 구간에서 지연됐는지 알 수 없다. pending 게이지도 대기 중인 수일 뿐 대기 시간이 아니다.

## Decision

- `TimedPaymentProvider`를 retry·회로 안, HTTP adapter 바로 밖에 둔다. `commerce.pg.call`은 adapter 시도 한 번의 풀 획득·연결·통신·응답 해석을 포함한다. retry 대기와 회로가 거절한 호출은 포함하지 않는다. 생성에도 같은 계측을 붙이되 생성 retry는 추가하지 않는다.
- 태그는 `operation`(`create`, `lookup_transaction`, `lookup_order`)과 `result`(관찰 종류, UNKNOWN의 이유, 전파된 예외의 `ERROR`)만 사용한다. 주문 번호·거래 키·카드 정보는 지표에 넣지 않는다. `OBSERVED`는 계약에 맞는 응답을 읽었다는 뜻이며 결제 SUCCESS와 같지 않다.
- `TimedPgConnectionManager`는 풀의 lease 요청부터 endpoint 획득 또는 실패까지를 `commerce.pg.pool.acquire`로 직접 잰다. 새 연결 수립과 PG 응답 대기는 제외한다. `pool`과 `result`(`acquired`, `timeout`, `error`, `cancelled`)를 기록하며 취소와 get 실패가 겹쳐도 lease당 한 번만 기록한다.
- Micrometer의 HttpClient 5 풀 binder로 max·leased·available·pending을 노출한다. 앱의 생성·조회는 기존처럼 `shared` 풀을 사용한다. 두 타이머는 Prometheus histogram을 내보낸다.
- 별도 프로세스의 가짜 PG는 handler 진입부터 응답 전송 종료까지를 직접 잰다. `/stats`의 수신 수와 처리 종료 표본 수를 구별한다. 이 측정에는 앱의 풀 획득·연결 수립이 포함되지 않는다.
- 계측은 infrastructure에 둔다. 결과 반환·예외 전파·retry·회로 집계·timeout·연결 한도·주문 트랜잭션 경계를 바꾸지 않는다. 느린 호출 회로 기준, 풀 분리, 알림은 이번 결정에서 채택하지 않는다.

## Alternatives and costs

- **adapter 전체 시간을 PG 처리 시간으로 사용:** 풀·연결·통신이 섞여 원인을 잘못 판단할 수 있다.
- **앱 p99에서 PG p99를 빼기:** 서로 다른 요청의 분위수이므로 구간의 p99가 되지 않는다. 풀 획득을 직접 계측하고 구간별 분포를 함께 본다.
- **완전한 요청별 tracing:** 같은 요청의 전체 경로 분석에 유용하지만 현재 비교에 필요한 범위를 넘는다. 이번 지표만으로 네트워크·Tomcat·DB 대기를 모두 나눴다고 주장하지 않는다.
- histogram은 시계열과 기록 비용을 늘린다. 고정된 종류·결과 태그만 사용한다. 가짜 PG의 통계는 로컬 실험용이며 처리 시간 표본을 메모리에 보관하므로 장기 운영 수집기로 사용하지 않는다.

## 검증

- `PaymentMetricsTest`: 실제 HTTP 결과 보존, retry마다 시도 표본, OPEN에서 시도 표본 없음, 생성 한 번, 동일 예외 전파, Spring 구성과 Prometheus histogram·기본 연결 한도.
- `PgConnectionMetricsTest`: 실제 단일 연결 풀에서 PG 도착 전 대기를 별도 측정, 풀 timeout은 PG 미도착, 취소와 get 실패 중복 기록 방지.
- 별도 가짜 PG에서 지연 중 수신·완료 구분, 처리 시간, 생성 202, 일회성 503, 초기화를 확인한다. 계측 조건과 기대값은 [시도 계측 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentMetricsTest.java)와 [풀 획득 계측 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgConnectionMetricsTest.java)에 둔다. 지속 부하에서의 보호 효과를 검증한 것은 아니다.
