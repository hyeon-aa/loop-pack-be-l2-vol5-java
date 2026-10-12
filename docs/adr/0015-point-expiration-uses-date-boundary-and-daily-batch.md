# ADR 0015: 포인트 적립 만료는 날짜 경계로 계산하고 매일 배치로 반영한다

Status: Accepted
Date: 2026-10-08
Refines: [ADR 0006](./0006-point-grants-expire-on-request.md)

## Context

[ADR 0006](./0006-point-grants-expire-on-request.md)은 적립 포인트가 지급 시각의 정확히 1년 후 만료되고, 요청 시 만료를 확인·반영하기로 했다. 요청이 없는 사용자의 저장 잔액은 만료 시각 이후에도 갱신되지 않을 수 있다는 한계가 있었다.

## Decision

요청 시 만료 확인은 유지하면서, 매일 한국 시간 00:00에 실행하는 만료 배치를 추가한다. 새 적립분은 한국 시간 기준 지급일의 1년 뒤 날짜까지 사용 가능하며 다음 날 00:00에 만료한다(정확히 1년 후 시각이 아니라 날짜 경계). 2월 29일 지급분은 다음 해 2월 28일까지 사용 가능하다. 기존 지급건에 저장된 만료 시각은 소급 변경하지 않는다.

`PointExpirationBatch`는 100명씩 사용자 ID를 조회하고 별도 `PointExpirationWorker` bean을 호출한다. 사용자 한 명의 만료 기록과 잔액 변경을 한 트랜잭션으로 묶는다. 실패는 바깥에서 로그로 기록한 뒤 다음 사용자를 처리하고, 즉시 재시도하지 않는다. 다음 배치에서 다시 처리할 수 있다. 배치 기준 시각은 시작 때 고정한다.

## Consequences

배치가 도입된 시점에는 포인트를 바꾸는 다른 요청과의 동시성 보호 방식이 아직 미정이었다. 따라서 자동 실행은 `point.expiration.scheduling-enabled=true`로 명시적으로 켜며 기본 비활성화한다. 이후 결제·충전·조회·배치가 같은 사용자 잔액 잠금과 지급 기록 잠금 조회를 사용하도록 구현했다 ([ADR 0010](./0010-point-balance-locks-before-ledger-read.md) 참고). 자동 배치 실행은 여전히 명시적으로 설정을 켜는 방식을 유지한다.

## Evidence

- [ADR 0006](./0006-point-grants-expire-on-request.md) — 만료 시각 규칙의 원 결정
- [ADR 0010](./0010-point-balance-locks-before-ledger-read.md) — 이후 적용된 동시성 보호
- [3주차 설계 2.4](../week3/design.md#24-포인트-결제충전만료)

- [3주차 구현 기록 4.2](../week3/implementation-log.md#42-날짜-기준-만료와-매일-배치) — 테스트 결과

## 검증 방법과 확인 범위

| 검증 | 준비·실행 | 확인한 결과 |
|---|---|---|
| 날짜와 경계 | 고정 시각으로 만료 직전·정확한 만료 시각 비교 | 직전에는 유지, 경계부터 만료. UTC 지급 시각을 한국 날짜로 계산하고 윤년 지급은 다음 해 2월 28일까지 유지 |
| 남은 적립만 만료 | 충전 1,000원·적립 500원에서 200원 사용 후 만료 배치 실행 | 잔액 1,300→1,000원, 충전 보존·남은 적립 300원만 만료 |
| 반복 실행 | 같은 사용자 배치 두 번 실행 | 잔액 1,000원·만료 기록 1건 유지 |
| 사용자별 rollback | 두 사용자 중 첫 사용자 변경을 flush·SQL 조회 후 예외 주입 | 첫 사용자 1,300원 복구, 둘째 1,000원. 성공 1·실패 1. 이후 배치에서 첫 사용자도 처리 |
| 페이지 경계 | 대상 사용자 101명 준비 | 100명 조회 경계를 넘어 101명 처리·만료 기록 101건 |

날짜는 [PointBalanceModelTest](../../apps/commerce-api/src/test/java/com/loopers/domain/point/PointBalanceModelTest.java)의 단위 검증이고, 저장·반복·rollback은 [PointExpirationBatchTest](../../apps/commerce-api/src/test/java/com/loopers/application/point/PointExpirationBatchTest.java)의 실제 DB 통합 검증이다. 요청 시 만료 저장은 PointExpirationPersistenceIntegrationTest, 결제·충전·배치 경쟁은 ADR 0010과 연결한다.

한국 시간 자정 cron·명시적 활성화는 스케줄러 코드의 설정을 확인했다. 실제 자정에 자동 호출되는지 검증한 것은 아니다. 배치 기준 시각 고정과 기존 만료 시각 비소급 처리도 구현 확인이며 위 표의 개별 테스트로 직접 입증한 것과 구분한다.
