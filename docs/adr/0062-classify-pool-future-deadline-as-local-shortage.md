# ADR 0062: 연결 획득 future의 기한 초과도 로컬 연결 부족으로 분류한다

Status: Accepted
Extends: [ADR 0060](0060-exclude-lookup-connection-shortage-from-circuit.md), [ADR 0061](0061-separate-pg-attempt-pool-acquisition-and-server-timing.md)

## Context

별도 PG에 연속 조회를 보내자 PG가 정상 응답한 조건에서도 일부 풀 획득 실패가 `TEMPORARY_FAILURE`로 분류됐다. 기존 adapter는 `ConnectionRequestTimeoutException`만 풀 부족으로 알아봤다.

HttpClient 5.4.2의 `InternalExecRuntime.acquireEndpoint`는 lease의 `get`이 직접 `TimeoutException`을 던지면 `ConnectionRequestTimeoutException`으로 바꾼다. 반면 HttpCore 5.3.3의 풀에서 future를 `DeadlineTimeoutException`으로 완료하면 `ExecutionException`을 거쳐 `RequestFailedException`이 된다. 둘 다 endpoint를 얻기 전이지만 예외 모양이 달랐다. 이 때문에 PG 미도착 시도가 회로의 실패 표본에 들어가고, 풀 타이머도 error로 기록됐다.

## Decision

- adapter는 원인 체인의 `ConnectionRequestTimeoutException`과 `RequestFailedException` 바로 아래의 `DeadlineTimeoutException`을 연결 획득 timeout으로 판단한다. 다른 `RequestFailedException`·IO 오류·연결 수립·응답 timeout은 이 조건으로 묶지 않는다.
- 해당 조회는 `CONNECTION_UNAVAILABLE`, 생성은 `NOT_SENT`로 분류한다. 조회 retry와 회로 집계 제외는 ADR 0060의 기존 규칙을 따른다.
- 풀 획득 타이머는 `ExecutionException`의 원인이 `TimeoutException`인 경우도 timeout으로 센다. lease 안이라는 경계에서만 판단하며 원래 예외 객체와 원인은 그대로 전파한다.
- retry·회로·timeout·연결 수의 숫자는 바꾸지 않는다. 생성 재전송도 추가하지 않는다.

## Alternatives and costs

- **직접 던진 timeout만 분류:** 같은 풀 기한 초과가 실행 순서에 따라 PG 실패가 되므로 유지하지 않는다.
- **모든 TimeoutException·RequestFailedException을 전역에서 로컬 실패로 분류:** 연결·응답 오류까지 확실한 미발송으로 오인할 수 있다. 알려진 획득 경로로 제한한다.
- **계측 manager가 예외를 다른 예외로 바꾸기:** 비계측 adapter에서는 문제가 남고 계측이 호출 의미를 바꾸므로 선택하지 않는다.
- 라이브러리의 예외 변환 경로에 의존한다. 의존성 변경 시 직접 timeout과 future 기한 초과 두 경로를 함께 검증한다.

## 검증

- `PaymentPoolDeadlineTest`: 실제 HttpClient에 실패하는 lease future를 연결한다. 비계측·계측 모두 생성은 NOT_SENT, 두 조회 경로는 retry 후 CONNECTION_UNAVAILABLE, 회로 표본 0·PG 수신 0이다. 다른 lease IO 실패는 기존 TEMPORARY_FAILURE를 유지한다.
- `PgConnectionMetricsTest`: future 기한 초과를 timeout 1·error 0으로 기록하며 동일한 예외 객체를 전파한다. 수정 전에는 신규 기대값 3건이 실패했다.
- 예외 경로의 재현 조건과 기대값은 [획득 기한 초과 회귀](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentPoolDeadlineTest.java)와 [풀 획득 계측 회귀](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgConnectionMetricsTest.java)에 둔다.
