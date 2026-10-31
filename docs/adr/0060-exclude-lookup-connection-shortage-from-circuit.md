# ADR 0060: 조회의 연결 부족은 회로 집계에서 빼고, retry는 유지한다

Status: Accepted
Follow-up validation: [실제 API의 생성·조회 경쟁과 OPEN 대기 비교](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentApiLoadExperimentTest.java), [배치 모델 대조](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentLookupBatchBackoffExperimentTest.java). 본문의 실험 회차와 후속 확인 계획은 결정 당시의 기록이며, 후속 검증의 재현 코드는 이 링크에서 구분한다.
Extended by: [ADR 0061](0061-separate-pg-attempt-pool-acquisition-and-server-timing.md) — 풀 획득과 PG 호출 시도의 관찰 분리
Extended by: [ADR 0062](0062-classify-pool-future-deadline-as-local-shortage.md) — 연결 획득 future 기한 초과의 동일 분류
Related: [ADR 0031](0031-pg-lookup-retry-circuit-breaker-and-fallback.md), [ADR 0034](0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md)

## 용어

이 문서의 **연결 부족**은 **풀 부족**과 같은 말이다. PG로 HTTP 요청을 보내려면 Apache HttpClient의 연결 풀(같은 주소당 5개)에서 연결을 하나 빌려야 한다. 5개가 모두 쓰이는 동안 연결 대기 한도(300ms)가 지나면 `ConnectionRequestTimeoutException`이 나고 요청은 PG에 닿지 않는다.

| 이 문서·코드의 말 | 같은 뜻의 말 | 가리키는 것 |
| --- | --- | --- |
| 연결 부족, 연결을 빌리지 못함, `CONNECTION_UNAVAILABLE` | 풀 부족, 풀 대기 실패, 풀 획득 실패 | HttpClient 연결 풀에서 연결을 기다리다 한도를 넘김. 우리 쪽 자원 문제 |
| 연결 대기(connection request timeout) | 풀 대기 시간 | 연결 풀에서 연결이 빌 때까지 기다리는 시간 |

비슷하지만 다른 것:

- **연결 실패**(새 연결 거부 `ConnectException`, 연결 수립 시간 초과 `ConnectTimeoutException`): 풀에서 연결을 빌린 뒤 PG와 새 연결을 맺다가 실패한 것이다. PG가 꺼졌거나 네트워크 문제일 수 있어 연결 부족에 넣지 않는다.
- **DB 커넥션 풀(HikariCP)·Tomcat 스레드 풀**: 같은 "풀"이지만 PG 호출용 HTTP 연결 풀이 아니다. 이 결정의 대상이 아니다.

## Context

조회 회로는 PG 조회 전체가 아픈지를 판단하는 장치다(ADR 0031). 그런데 adapter는 조회가 우리 쪽 연결 풀에서 연결을 빌리지 못한 경우(`ConnectionRequestTimeoutException`)도 PG의 timeout·5xx와 같은 `TEMPORARY_FAILURE`로 돌려줬고, 회로는 이를 실패로 셌다. 실험 E 9회차에서 PG가 받은 GET 5개를 모두 정상 처리했는데도, 연결을 못 빌린 조회의 실패만으로 회로가 열렸다.

10회차에서는 연결 부족을 실패 대신 성공 표본으로 넣는 후보만 비교했다. 회로는 결과를 실패 아니면 성공으로만 기록하므로(`recordResult`), 실패도 성공도 아니게 표본에서 빼는 방식은 비교하지 않은 상태였다.

ADR 0034는 생성 POST의 연결 단계 실패만 `NOT_SENT`로 나눴고, 조회의 연결 실패는 결과를 확정하지 않으므로 `TEMPORARY_FAILURE`로 남겼다.

## Decision

- adapter는 조회 GET이 연결을 빌리지 못하면 `UnknownCause.CONNECTION_UNAVAILABLE`을 돌려준다. 새 연결 거부·연결 수립 시간 초과·응답 timeout·5xx는 PG 쪽에서 생긴 문제일 수 있어 그대로 `TEMPORARY_FAILURE`다.
- 회로는 `CONNECTION_UNAVAILABLE`을 **집계에서 뺀다.** 회로 안에서 이 결과를 무시할 예외로 바꿔 던지고, `ignoreExceptions`에 등록한다. 무시한 예외는 회로의 허가만 돌려주고 창에 넣지 않는다. 그 밖의 집계 규칙(`TEMPORARY_FAILURE`만 실패, 대답을 들은 결과와 예상 밖 응답은 성공)은 그대로다.
- retry는 연결 부족도 계속 재시도한다. 남은 예산 조건은 `TEMPORARY_FAILURE`와 같다. 재시도가 끝나도 못 빌리면 고객에게는 `CONNECTION_UNAVAILABLE`의 UNKNOWN을 돌려주고, 결제 결과는 확정하지 않는다.
- 생성 POST의 분류(ADR 0034의 `NOT_SENT`)와 회로·retry 숫자는 바꾸지 않는다.

## Alternatives and costs

- **실패로 셈 (기존)**: PG가 느리면 가장 일찍 열린다. 대신 PG가 정상이어도 우리 연결이 모자라면 열려, 정상 조회까지 막는다.
- **성공으로 셈**: PG가 정상일 때는 열리지 않는다. 대신 연결 부족의 성공 표본이 실패율의 분모에 들어간다. 창 20·최소 표본 4에서는 실제 PG 실패(503·응답 timeout)의 실패율이 25%로 낮아져 회로가 열리지 않았다.
- **집계에서 뺌 (선택)**: 창에는 PG까지 간 조회만 남는다. 비교한 장면에서 정상 PG는 막지 않고 503·느린 PG는 막았다.
- **연결 부족을 retry 대상에서도 빼기**: 고객 대기는 줄지만, 400ms에 연결이 돌아오는 장면에서 retry로 확인하던 조회를 잃는다. 이번에는 회로 집계만 바꿨다.
- **대가와 한계**
  - 회로는 이제 우리 쪽 연결 부족으로부터 PG를 보호하지 않는다. 연결 부족 자체는 연결 수·조회 동시 실행 수·풀 분리로 다뤄야 하고, 이 장치들은 앱에 적용하지 않았다.
  - 회로가 막아 주던 연결 부족 조회의 retry가 이제 계속 연결을 다툰다. 같은 풀을 쓰는 생성 POST에 미치는 영향은 실험 결과로 확인한다.
  - PG가 느려 연결이 오래 묶이는 장면은 응답 timeout 실패로만 알아챈다. 응답 timeout 안의 느린 호출 비율로 여는 기준은 없다.
  - 비교한 장면은 동시 조회 20개·연결 5개·연결 대기 300ms 하나다. 창 20은 현재 설정이 아니라 비교 조건이다. 최소 표본을 연결 수보다 크게 잡을 때의 감지 지연, 지속 부하는 재지 않았다.

## 검증

재현 조건과 검증 기대값은 [연결 부족 집계 비교 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentLookupPoolFailureCountingExperimentTest.java)와 [연결 부족 집계 제외 회귀](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentLookupCircuitBreakerTest.java)에 둔다.

- `PaymentLookupPoolFailureCountingExperimentTest`: 실패로 셈·성공으로 셈·제외를 PG 정상·느림·503과 창 4·20에서 비교. 제외만 모든 장면에서 정상 PG는 막지 않고 503·느린 PG는 막았다. 창 20에서 성공으로 셈은 503·느린 PG를 실패율 25%로 놓쳤다.
- `PaymentProviderContractTest`: 조회 5개가 연결을 모두 잡은 동안의 조회는 PG에 닿지 않고 `CONNECTION_UNAVAILABLE`.
- `PaymentLookupCircuitBreakerTest`: 연결을 못 빌린 조회 두 번은 retry로 시도 4번 이상을 했지만 창의 표본 0·CLOSED. 실패로 세던 기존 규칙이면 OPEN이 됐을 장면이다.
- 두 테스트는 구현 전 코드에서 실패하는 것을 확인했다.
