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
| 현재 ID별 삭제 | 삭제 commit 전에 등록 완료 | 삭제 commit 전에 등록 완료 |
| RR 벌크 | 삭제 commit까지 INSERT 대기 | 삭제 commit 전에 등록 완료 |
| RR·힌트 벌크 | 삭제 commit까지 INSERT 대기 | 삭제 commit 전에 등록 완료 |
| RC 벌크 | 삭제 commit 전에 등록 완료 | 삭제 commit 전에 등록 완료 |

A의 RR 벌크·힌트에서는 브랜드 인덱스 끝 구간의 supremum 잠금이 INSERT의 `X,INSERT_INTENTION`과 충돌했다. B에서는 기존 삭제 상품 레코드 앞의 `X,GAP`이 있었고 supremum 잠금은 없었다. 대기 기록뿐 아니라 삭제 commit 전 data_locks도 직접 확인했다. B는 등록이 기다리지 않았다는 결과만으로 해석한 사례가 아니다.

원본은 [INSERT 잠금 목록](../week3/implementation-log.md#잠금-목록)과 [INSERT 대기 상세](../week3/implementation-log.md#insert-대기)에 남겼다. 두 fixture의 관찰을 모든 분포·등록 요청의 보장으로 확대하지 않으며, 명시된 잠금 목록만 보고 내부·암묵적 잠금까지 모두 하나라고 설명하지 않는다.

같은 브랜드의 새 등록 차단은 갭 잠금에 의존하지 않는다. 등록은 브랜드 FOR SHARE, 삭제는 FOR UPDATE를 얻으므로 순서가 정해진다. RC 후보에서도 등록이 먼저면 새 상품까지 삭제되고, 삭제가 먼저 commit되면 등록이 거절되며 rollback되면 등록 가능함을 확인했다.

### 비교 표의 각 항목을 확인한 방법

표의 세 항목은 서로 다른 데이터와 검사로 확인했다. 기존 상품 잠금 결과를 새 INSERT 결과로 추정하지 않는다. `바로 완료`는 응답 시간이 0이라는 뜻이 아니라 **삭제 transaction의 commit 전에 요청이 완료됨**을 뜻한다.

#### 다른 브랜드의 기존 상품을 잠글 때

삭제를 실행하고 flush한 뒤 아직 commit하지 않은 상태에서, 별도 JDBC 연결로 다른 브랜드의 기존 상품 PK 하나를 `SELECT ... FOR UPDATE NOWAIT` 조회했다. 잠금을 얻고 행이 실제로 반환되면 비충돌, MySQL 오류 **3572**면 충돌로 판정한다. 그 밖의 SQL 오류나 행 누락은 테스트 실패다. 검사 연결은 rollback해 잠금을 해제한다. 비교에서는 삭제 대상 상품이 잠겨 있다는 것도 검사해 삭제가 실행되지 않아 통과하는 경우를 막는다.

| 표의 방식 | 데이터·판정과 테스트 근거 |
| --- | --- |
| ID별 잠금 | 두 브랜드의 상품을 준비하고 실제 BrandFacade.delete 후 다른 브랜드 PK 잠금을 얻는지 검사. `BrandConcurrencyTest.deletingOneBrandDoesNotLockAnExistingProductOfAnotherBrand`. 별도 분포 비교의 CURRENT 경로에서도 네 조건을 관찰 |
| RR 벌크 | 여섯 분포·통계 조건에서 브랜드 조건 UPDATE 후 검사. `BrandHintComparisonTest.comparesPlansLocksAndCommittedDeletionTime`의 RR_PLAIN. 5/6은 그 실행의 관찰값이며 실행 계획과 무관하게 반드시 5/6이어야 한다고 고정하지 않음 |
| RR·힌트 벌크 | 같은 여섯 조건의 RR_HINT. EXPLAIN이 기존 브랜드 인덱스를 선택하는지, 검사한 다른 브랜드 상품이 비충돌인지 직접 검사 |
| RC 벌크 | 같은 여섯 조건의 RC_PLAIN. 실제 연결이 RC인지 확인하고 검사한 다른 브랜드 상품의 비충돌을 직접 검사 |

후보 업무 검증에서도 다른 브랜드 수정의 양방향 실행을 확인한다. RC·RR 힌트는 첫 transaction이 commit하지 않아도 뒤 요청이 완료돼야 한다. RR 일반 벌크는 실제 계획이 전체 스캔이면 대기를 관찰하고, 좁은 검색이면 commit 전 완료를 확인한다. 대기가 생긴 경우에도 commit 뒤 수정 결과와 대상 삭제가 보존되는지 확인한다.

#### 다른 브랜드에 새 상품을 등록할 때

`BrandHintComparisonTest.observesInsertsIntoAnEmptyOtherBrand`가 네 방식 × 기존 삭제 상품 유무의 **8개 조합**을 실행한다. 두 브랜드는 활성이고 브랜드 2에는 상품이 없다. 브랜드 1에는 활성 상품 하나만 넣거나, 여기에 삭제 시각이 2020-01-02인 상품을 하나 더 넣는다. 실제 연결의 격리 수준은 각 전략에 맞게 확인한다.

1. 삭제 transaction에서 브랜드 1 삭제와 상품 삭제 SQL을 실행·flush한다. ID별은 실제 Facade, 대안은 테스트 전용 SQL을 사용한다.
2. commit 전 별도 root 연결로 product의 data_locks를 읽는다. RR 힌트는 기존 삭제 상품이 없으면 supremum 잠금, 있으면 X,GAP과 supremum 부재를 직접 검사한다.
3. 별도 worker에서 실제 `ProductFacade.create(brandId=2, ...)`를 호출한다.
4. 300ms 안에 요청이 완료되면 삭제 commit 전 등록 완료로 판정한다. 완료되지 않았다면 data_lock_waits에서 실제 product INSERT 잠금을 확인한다. 시간 초과만으로 잠금 대기라 판단하지 않으며, 연결·스케줄링·SQL 오류로 검사에 실패하면 테스트도 실패한다.
5. 삭제를 commit하고 worker가 10초 안에 완료되는지 확인한다. 새 상품의 브랜드가 2이고 미삭제인지, 브랜드 1의 활성 상품이 0개인지, 기존 삭제 상품의 시각이 보존됐는지 DB에서 확인한다. finally에서 worker 종료를 확인한 뒤 데이터를 정리한다.

표의 모든 INSERT 칸은 기대값도 직접 검사한다. ID별·RC는 두 조건 모두 commit 전 완료, RR 벌크·RR 힌트는 기존 삭제 상품이 없을 때만 대기하고 있을 때는 commit 전 완료해야 한다. 이 기대값은 해당 두 fixture의 회귀 기준이며 모든 분포의 보장은 아니다.

#### 삭제 대상인 같은 브랜드에 새 상품을 등록할 때

| 표의 방식 | 직접 실행한 서비스와 테스트 근거 |
| --- | --- |
| ID별 잠금 | 실제 BrandFacade·ProductFacade의 공유/배타 잠금과 두 처리 순서 검사. `BrandConcurrencyTest.registrationKeepsSharedBrandLockUntilCommit`, `waitingRegistrationReadsDeletionAfterCommit`, `deletionIncludesProductWhenRegistrationTransactionCommitsFirst`, `rolledBackDeletionReleasesBrandForRegistration` |
| RR 벌크 | `BrandRrBulkValidationTest`의 registrationFirstIsIncludedAfterItsCommit와 deletionFirstRegistrationObservesCommitOrRollback |
| RR·힌트 벌크 | `BrandRrHintBulkValidationTest`의 동일한 등록 경쟁 사례 |
| RC 벌크 | `BrandRcBulkValidationTest`의 동일한 등록 경쟁 사례 |

후보들은 공통 테스트를 재사용하되 실제 격리 수준과 SQL이 다른 Spring bean으로 실행한다. 등록이 먼저면 공유 브랜드 잠금을 유지한 채 삭제를 시작하고, 삭제가 먼저면 배타 브랜드 잠금을 유지한 채 등록을 시작한다. 후보 경쟁은 data_lock_waits에서 해당 brand PK의 대기를 확인한 뒤 commit/rollback한다. ID별 검증은 별도 연결의 NOWAIT 잠금 검사와 실제 서비스 경쟁을 함께 사용한다.

최종 결과는 등록 먼저 commit이면 새 상품까지 삭제, 삭제 먼저 commit이면 등록 NOT_FOUND와 활성 상품 0개, 삭제 rollback이면 등록 성공이다. 따라서 표의 `브랜드 행 잠금으로 막음`은 등록이 영구 금지된다는 의미가 아니라 먼저 진행한 transaction이 끝날 때까지 기다린다는 뜻이다. Controller 진입의 신규 transaction과 바깥 RR transaction에 REQUIRED로 합류하는 경우도 별도로 검사한다.

실제 업무 원자성과 경합을 검증한 범위이며 높은 부하의 지연·모든 상품 행·모든 INSERT 위치를 검증한 결과는 아니다. 세부 실행 결과와 전체 검사 건수는 [구현 기록](../week3/implementation-log.md)에 남긴다.

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
- [RR 일반 벌크 후보 업무 검증](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRrBulkValidationTest.java)
- [현재 ID별 등록·삭제 경쟁](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandConcurrencyTest.java)
- [RR 힌트 후보 업무 검증](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRrHintBulkValidationTest.java)
- [계획·잠금·시간·INSERT 비교](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandHintComparisonTest.java)
- [3주차 설계](../week3/design.md#22-상품-등록과-브랜드-삭제)
- [검증 기록](../week3/implementation-log.md)
- [MySQL 문장별 잠금](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)
- [MySQL 격리 수준](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)
- [MySQL 인덱스 힌트](https://dev.mysql.com/doc/refman/8.0/en/index-hints.html)
- [Spring REQUIRED 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
