# ADR 0019: 브랜드 상품 삭제의 벌크 UPDATE 대안 비교

Status: Accepted
Date: 2026-10-09

## Context

현재는 브랜드를 잠근 뒤 활성 상품 ID를 구하고, ID 오름차순으로 상품을 하나씩 잠금 조회하여 삭제한다([ADR 0016](0016-brand-deletion-locks-products-by-id.md)). 대상과 잠금 순서는 명확하지만 상품 수만큼 조회·UPDATE가 늘어난다.

현재 `ProductModel.markDeleted()`는 삭제 시각을 표시한다. 재고의 `Stock.decrease()`처럼 상품마다 판단할 규칙이 복잡하지 않으므로, 브랜드 조건의 벌크 UPDATE로 SQL을 줄일 수 있는지 검토했다. 브랜드·상품의 전체 rollback과 등록·주문·수정 경쟁의 기존 결과를 유지하는 것이 전제다.

## Decision

**기존 ID별 잠금·삭제와 REPEATABLE READ를 유지한다.** 벌크·RC·인덱스 힌트는 대안으로 비교했고 실제 서비스에는 적용하지 않는다.

대안이 업무 검증에서 실패했거나 RC를 국소 적용할 수 없어서 내린 결정은 아니다. 현재 구현의 명시적인 대상·잠금 순서와 기존 모델 행동을 유지하고, 상품 수에 비례하는 SQL 비용을 받아들인다. 삭제 지연이나 데이터 규모가 문제가 되면 **브랜드 행 잠금 + 삭제 트랜잭션만 RC + 벌크 UPDATE**를 먼저 재검토한다.

## 1. 벌크 UPDATE로 SQL을 줄일 수 있었다

브랜드 배타 잠금과 브랜드·상품을 함께 commit/rollback하는 경계는 유지하고, 상품 삭제 부분만 다음 한 문장으로 비교했다.

```sql
UPDATE product
SET deleted_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
WHERE brand_id = :brandId AND deleted_at IS NULL;
```

`deleted_at IS NULL`로 이미 삭제된 상품의 삭제 시각을 보존한다. 벌크는 JPA 변경 감지·콜백을 거치지 않으므로 `updated_at`도 직접 갱신한다. 상품 엔티티를 미리 읽거나 이후 다시 저장하지 않았고, 실제 브랜드·상품 SQL 실행 뒤 실패하면 전체 rollback되는 것도 확인했다.

로컬 JDK 21·MySQL Testcontainers에서 두 브랜드의 상품 ID를 교차 적재했다. 방식별 준비 1회 뒤 순서를 교대해 3회 측정한 중앙값이며, 구간은 트랜잭션 시작부터 commit까지다. 기존 `(brand_id, deleted_at)` 인덱스를 유지했고 인덱스 힌트·명시적 통계 갱신은 적용하지 않았다.

| 브랜드당 상품 수 | ID별 중앙값(ms) | 벌크 중앙값(ms) | SQL 준비 횟수: ID별 → 벌크 |
| --- | ---: | ---: | ---: |
| 10 | 44.699 | 9.245 | 23 → 3 |
| 100 | 209.049 | 7.529 | 203 → 3 |
| 1,000 | 1,551.608 | 26.810 | 2,003 → 3 |

SQL 수는 Hibernate prepareStatementCount이며 네트워크 왕복 수와 동일한 지표는 아니다. 작은 로컬 표본이고 잠금 보유 시간만 따로 측정하거나 운영 성능을 검증한 것은 아니다.

## 2. RR에서는 실행 계획에 따라 잠금 영향이 넓어졌다

삭제 SQL 이후 commit 전에 다른 브랜드의 기존 상품 한 행을 `FOR UPDATE NOWAIT`로 잠가 봤다. ID별 방식에서는 잠금을 얻었지만, RR 벌크는 일부 조건에서 충돌했다. 일반 SELECT의 차단을 검사한 결과는 아니다.

해당 벌크 EXPLAIN은 브랜드 인덱스가 있어도 `key=PRIMARY`, `type=index`를 선택했다. WHERE에 맞아 반환되거나 변경되는 행만으로 잠금 범위를 판단할 수 없었다. 실제 검색 경로와 격리 수준이 영향을 줬다.

브랜드당 100개를 두고 브랜드 2/100개·ANALYZE TABLE 전후를 비교했다. 대상과 첫 다른 브랜드의 상품 ID는 교차했고, 나머지 98개 브랜드는 뒤에 적재했다. 대상 브랜드의 기존 삭제 상품도 하나 있었다.

| 브랜드 수 | 통계 갱신 | RR 벌크 실행 계획 | 검사한 다른 브랜드 상품의 잠금 충돌 |
| --- | --- | --- | --- |
| 2 | 전 | PRIMARY 스캔 | 있음 |
| 2 | 후 | PRIMARY 스캔 | 있음 |
| 100 | 전 | PRIMARY 스캔 | 있음 |
| 100 | 후 | 브랜드 인덱스 범위 검색 | 없음 |

ID별 방식은 네 조건 모두 검사한 행에 충돌이 없었다. 벌크도 항상 다른 브랜드를 막는 것은 아니었다. 정상 동시 삭제·수정이 완료된 결과와, commit을 잠시 늦춰 실제 대기를 드러낸 결과는 구분한다. 모든 다른 브랜드 행과 INSERT를 확인한 것은 아니다.

## 3. 삭제 트랜잭션만 RC로 바꾸는 대안

RC의 UPDATE는 조건 불일치 레코드의 잠금을 해제하며, 이미 잠긴 행의 조건 판단에는 semi-consistent read를 사용할 수 있다. 일반적인 검색의 갭 잠금도 줄어든다. 이는 불필요한 잠금 유지를 줄이는 성질이며 외래키·중복키 검사·인덱스 변경의 모든 잠금이나 교착을 없애는 보장은 아니다.

같은 네 조건에서 RR 벌크는 3/4, RC 벌크는 0/4의 기존 상품 잠금 충돌을 관찰했다. 실제 다른 브랜드 수정도 RC에서는 삭제 commit 전에 완료됐다.

애플리케이션 전체를 RC로 바꿀 필요는 없다. 새 트랜잭션을 시작하는 `BrandFacade.delete()`에 `@Transactional(isolation = Isolation.READ_COMMITTED)`를 지정하는 방법을 사용할 수 있다. 현재 Controller는 트랜잭션 없이 Facade 프록시를 호출한다. 다만 REQUIRED로 기존 RR 트랜잭션에 합류하면 바깥 RR을 따른다. 상품 벌크만 REQUIRES_NEW로 분리하면 독립 commit되어 원자성이 깨질 수 있으므로 브랜드 잠금부터 삭제까지 한 경계를 유지한다.

테스트 전용 후보에서 새 RC 시작·기존 RR 참여, 등록·삭제 양방향 경쟁, 같은 상품의 주문·수정 경쟁, 실제 SQL 뒤 전체 rollback과 기존 HTTP 응답을 확인했다. 검증한 업무 결과는 유지됐다. 실제 운영 구현으로 옮긴 경우의 검증과는 구분한다.

## 4. RR을 유지하고 기존 인덱스에 힌트를 주는 대안

인덱스 힌트는 새 인덱스를 추가하는 것이 아니라 기존 `idx_product_brand_deleted`를 사용하도록 DB 선택에 개입한다.

```sql
UPDATE product FORCE INDEX (idx_product_brand_deleted)
SET deleted_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
WHERE brand_id = :brandId AND deleted_at IS NULL;
```

앞의 네 조건에 브랜드 2개·상품 1,000개씩인 통계 갱신 전후를 더해 비교했다. RR 힌트 후보도 RC 후보와 같은 등록·주문·수정·rollback 업무 사례를 유지했다.

| 방식 | 여섯 조건의 실행 계획 | 다른 브랜드 기존 상품 한 행의 잠금 충돌 |
| --- | --- | --- |
| RR·힌트 없음 | 조건에 따라 PRIMARY 스캔 | 5/6 |
| RR·브랜드 인덱스 힌트 | 브랜드 인덱스 range | 0/6 |
| RC·힌트 없음 | 조건에 따라 PRIMARY 스캔 | 0/6 |

단독 삭제 시간은 조건마다 더 빠른 전략이 달랐다. 조건별 준비 1회·측정 3회이며 전략 실행 순서도 고정이므로 전체 성능 우위를 단정하지 않는다. 표본은 [단독 삭제 비교 원본](../week3/implementation-log.md#단독-삭제-비교)에 보존한다. 다른 실행의 ID별 비교 시간과 합쳐 개선 배율을 계산하지 않는다.

힌트는 검색 경로에 개입하지만 전체 실행 계획이나 RR 잠금 규칙을 고정·변경하는 것은 아니다. SQL이 인덱스 이름에 의존하고, 분포가 바뀌면 그 경로가 계속 유리한지 관리해야 한다.

## 5. 기존 상품 비충돌과 새 INSERT 비충돌은 달랐다

작은 fixture에서 브랜드 1의 활성 상품 하나를 삭제하고 commit 전 유지한 뒤, 상품이 없는 브랜드 2에 실제 등록 요청을 보냈다. 브랜드 1의 기존 삭제 상품 유무를 나눠 비교했다.

| 방식 | 기존 삭제 상품 없음(A) | 기존 삭제 상품 있음(B) |
| --- | --- | --- |
| 현재 ID별 삭제 | 등록 commit 전 완료 | 등록 commit 전 완료 |
| RR 벌크 | 삭제 commit까지 INSERT 대기 | 등록 commit 전 완료 |
| RR·힌트 벌크 | 삭제 commit까지 INSERT 대기 | 등록 commit 전 완료 |
| RC 벌크 | 등록 commit 전 완료 | 등록 commit 전 완료 |

A의 RR 벌크·힌트에서는 브랜드 인덱스 끝 구간의 supremum 잠금이 INSERT의 `X,INSERT_INTENTION`과 충돌했다. B에서는 기존 삭제 상품 레코드 앞의 `X,GAP`이 있었고 supremum 잠금은 없었다. 대기 기록뿐 아니라 삭제 commit 전 data_locks도 직접 확인했다. B는 등록이 기다리지 않았다는 결과만으로 해석한 사례가 아니다.

원본은 [INSERT 잠금 목록](../week3/implementation-log.md#잠금-목록)과 [INSERT 대기 상세](../week3/implementation-log.md#insert-대기)에 남겼다. 두 fixture의 관찰을 모든 분포·등록 요청의 보장으로 확대하지 않으며, 명시된 잠금 목록만 보고 내부·암묵적 잠금까지 모두 하나라고 설명하지 않는다.

같은 브랜드의 새 등록 차단은 갭 잠금에 의존하지 않는다. 등록은 브랜드 FOR SHARE, 삭제는 FOR UPDATE를 얻으므로 순서가 정해진다. RC 후보에서도 등록이 먼저면 새 상품까지 삭제되고, 삭제가 먼저 commit되면 등록이 거절되며 rollback되면 등록 가능함을 확인했다.

## Follow-up

현재 ID별 방식은 명확한 잠금 대상·순서를 유지하는 대신 SQL 증가 비용이 남는다. 실제 삭제 지연·규모가 문제가 되면 국소 RC·벌크를 먼저 검토한다. RR·힌트도 업무 결과는 유지했지만 위 INSERT 대기와 인덱스 이름 의존이 남았다. 이 판단은 RC가 항상 빠르거나 모든 대기·교착을 해결한다는 뜻은 아니다.

| 후속 대안 | 검토할 비용·조건 |
| --- | --- |
| 브랜드 잠금 + 삭제만 RC + 벌크 | 실제 저장소·Facade에 옮긴 뒤 동일 계약·경쟁·rollback 검증을 연결하고 적용 환경의 지연 확인 |
| RR + 힌트 + 벌크 | 관찰한 갭 잠금과 인덱스 이름 의존, 다른 분포에서의 계획·비용 관리 |
| 같은 트랜잭션의 JDBC batch | 여러 UPDATE를 묶어 전송하되 마지막에 한 번 commit. 개별 SQL·잠금 비용은 남음. 아직 비교하지 않음 |
| 비동기 분할 삭제 — 상품을 나눠 삭제하고 묶음마다 commit | 앞선 commit은 뒤 실패로 전체 원복할 수 없음. 삭제 중 상태·노출·주문 차단·실패 재개 정책 필요. 아직 비교하지 않음 |

비동기 분할 삭제는 현재의 전체 원복 요구를 바꾸므로 정책 변경 없이 채택하지 않는다. 높은 동시 부하·대규모 데이터·모든 실행 계획에서의 지연과 교착 부재는 이번 비교 범위 밖이다.

## Evidence

- [ID별·벌크 비교](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandBulkDeletionExperimentTest.java)
- [RC 후보 업무 검증](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRcBulkValidationTest.java)
- [RR 힌트 후보 업무 검증](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRrHintBulkValidationTest.java)
- [계획·잠금·시간·INSERT 비교](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandHintComparisonTest.java)
- [3주차 설계](../week3/design.md#22-상품-등록과-브랜드-삭제)
- [검증 기록](../week3/implementation-log.md)
- [MySQL 문장별 잠금](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)
- [MySQL 격리 수준](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)
- [MySQL 인덱스 힌트](https://dev.mysql.com/doc/refman/8.0/en/index-hints.html)
- [Spring REQUIRED 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
