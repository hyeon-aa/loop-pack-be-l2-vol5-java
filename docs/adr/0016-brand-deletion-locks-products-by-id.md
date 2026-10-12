# ADR 0016: 브랜드 삭제는 상품 ID를 구한 뒤 개별 행을 잠근다

Status: Accepted
Date: 2026-10-09

## Context

브랜드 조건으로 상품을 잠금 조회하자 삭제하지 않는 다른 브랜드의 기존 상품까지 잠기는 현상을 재현했다. 결과 정렬만으로 잠금 순서와 범위를 충분히 표현하지 못했다.

## Decision

브랜드 변경 잠금을 얻은 뒤 활성 상품 ID만 일반 조회하고, ID 오름차순으로 PK 잠금 조회·삭제 상태 재검사를 한다. 엔티티는 잠금을 얻은 뒤 처음 읽는다. 후보 ID 조회를 위한 `(brand_id, deleted_at)` 인덱스를 추가한다.

## Alternatives and costs

인덱스가 있는 범위 잠금 조회도 가능하지만 경계 잠금과 실행 계획의 영향을 받는다. ID별 조회는 왕복 횟수가 늘어나는 대신 잠금 요청 순서와 기존 대상 행을 명시할 수 있다.

현재 BrandFacade는 브랜드 잠금 이전에 일반 스냅샷 조회를 하지 않는다. 잠금 이후 첫 스냅샷에서 ID를 구하며, 등록도 브랜드 잠금에 참여하고 상품의 브랜드는 변경 불가라는 전제가 있다. 과거 스냅샷을 만든 외부 트랜잭션에 이 흐름을 합치거나 브랜드 이동을 추가하면 다시 검토한다. 인덱스는 신규 스키마 생성에 반영하며 기존 로컬 DB 이관은 수행하지 않는다.

기존 PK 한 건을 유니크 동등 조건으로 찾는 잠금 조회는 해당 인덱스 레코드를 잠그며 앞의 갭은 잠그지 않는다. 이는 없는 ID 조회나 이후 UPDATE의 보조 인덱스·제약 검사까지 “항상 잠금 하나뿐”이라고 보장하는 뜻은 아니다. 원래 범위 SELECT의 다른 브랜드 행 충돌 관찰과 후속 벌크 UPDATE의 EXPLAIN 결과는 서로 다른 실험이다. 벌크·국소 RC 대안은 [ADR 0019](0019-brand-bulk-deletion-comparison.md)에 둔다.

## Evidence

- [설계 2.2](../week3/design.md#22-상품-등록과-브랜드-삭제)
- [구현 기록 2.2](../week3/implementation-log.md#22-상품-등록과-브랜드-삭제)

## 검증 방법과 확인 범위

| 검증 | 준비·실행 | 확인한 결과 |
|---|---|---|
| 다른 브랜드 기존 상품 | 실제 삭제 Facade 호출 후 커밋 전 다른 브랜드 상품을 PK FOR UPDATE NOWAIT로 조회 | 검사한 행의 잠금 즉시 획득. 후보 ID·PK 조회 EXPLAIN도 출력 |
| 등록 직후 대상 포함 | 등록이 브랜드 공유 잠금을 유지한 상태에서 삭제 요청 실행 | 등록 커밋 뒤 삭제가 새 상품까지 포함 |
| 여러 상품 순서 | 반대 품목 순서 주문 두 건 동시 실행 | 둘 다 성공, 품목별 수량 보존. ID 오름차순 조회는 구현과 대조 |
| 삭제와 주문 | 실제 삭제·확정 동시 실행 | 삭제 우선이면 주문 거절·재고 보존. 확정 성공이면 차감 재고를 유지한 채 삭제 |
| 다른 브랜드 새 INSERT | 현재 ID별 방식으로 삭제를 미커밋 상태로 유지하고 빈 다른 브랜드에 실제 등록 | 대상 브랜드의 기존 삭제 상품 유무 두 경우 모두 삭제 커밋 전에 등록 완료. 새 상품 활성·기존 삭제 시각·대상 삭제 결과 확인 |

기존 구현은 [BrandConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandConcurrencyTest.java)와 [StockConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/StockConcurrencyTest.java), INSERT 비교는 BrandHintComparisonTest로 확인한다. 네 방식의 비교 조건·잠금 검사 절차는 [ADR 0019](0019-brand-bulk-deletion-comparison.md)에 둔다.

한 행의 NOWAIT 성공을 모든 데이터 배치에서 무충돌이라고 확대하지 않는다. EXPLAIN도 이후 계획을 고정하지 않는다. 과거 스냅샷을 가진 바깥 RR 트랜잭션 합류와 상품 브랜드 이동은 현재 전제 밖이므로 검증 완료로 보지 않는다.
