<a id="query-contract"></a>

## 조회 계약과 측정 환경

상품 10,000건에서 SQL 원리를 확인하고, 100,000건을 복사한 실험 DB에서 실제 앱을 비교한다. 원본 DB·개발 DB와 분리한 benchmark MySQL 8.0.46·InnoDB·REPEATABLE READ를 사용한다. 기존 상품 인덱스와 상품별 좋아요 카운터를 유지한다. 설정은 [setup.md](setup.md)를 따른다.

가격순은 `price ASC, createdAt DESC, id DESC`다. 브랜드 필터·삭제 상품/브랜드 제외·상품 표시 필드·현재 좋아요 수·page/size 검증을 지킨다. 좋아요순은 전체 후보의 좋아요 수로 정렬한 뒤 페이지를 선택한다. 빈 페이지는 빈 items로 반환하고 totalCount·hasNext는 제공하지 않는다.

총상품수 제외 결정의 이유와 응답 영향은 [ADR 0022](../adr/0022-product-list-without-total-count.md#이유와-결정)를 따른다.

<a id="product-index-experiments"></a>

## 상품 인덱스 실험

각 절의 인덱스 추가·삭제·유지는 해당 실험 시점의 기록이다. 현재 인덱스별 용도와 남은 판단은 [현재 조회 흐름과 인덱스](#current-query-flow)에서 함께 확인한다.

### 준비: 브랜드 7 조건으로 범위를 좁힌 조회

처음에는 전체 활성 상품을 가져와 Java에서 브랜드 7을 골랐다. 브랜드 조건을 SQL에 전달하면서 기존 `(brand_id, deleted_at)` 인덱스로 해당 구간을 찾게 됐다. 이 준비 단계에서는 정렬·LIMIT을 넣지 않았다.

```sql
SELECT p.*
FROM product p
WHERE p.brand_id = 7 AND p.deleted_at IS NULL;
```

전체 조회는 상품 10,000개를 훑고 9,699개를 반환했다. 브랜드 조건 조회는 99개를 읽고 반환했다. 인덱스를 새로 추가한 효과가 아니라 기존 인덱스로 필요한 범위를 찾도록 조건을 DB에 전달한 효과다. [실행 SQL](../../scripts/sql/week4-01-brand-filter.sql).

구조를 설명하면 Root·Branch를 거쳐 브랜드 7·미삭제 구간을 찾고 Leaf 항목을 따라 읽는다. SELECT p.\*에는 이름·가격처럼 기존 보조 인덱스에 없는 컬럼이 있으므로 기본키로 상품 행도 가져와야 한다. 이 그림은 구조 설명이며 실제 페이지 방문을 측정한 것은 아니다.

### 1. 가격순 정렬만 추가

상품 99개를 가격순으로 반환하기 위해 ORDER BY만 추가했다. 같은 가격의 순서도 일정하게 유지하려고 생성 시각·ID 내림차순을 포함한다.

```sql
SELECT p.*
FROM product p
WHERE p.brand_id = 7 AND p.deleted_at IS NULL
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;
```

기존 브랜드 인덱스에는 가격 순서가 없으므로 해당 상품 99개를 읽은 뒤 별도로 정렬한다. SQL 반환도 99개다. **이 변경은 결과 순서를 맞추며 읽는 상품 수를 줄이지 않는다.** LIMIT·새 인덱스·좋아요는 이번 단계에 추가하지 않았다. [실행 SQL](../../scripts/sql/week4-02-price-sort.sql).

현재 읽기 흐름은 다음과 같다.

```text
브랜드 인덱스의 해당 구간 찾기
→ 상품 99개 읽기
→ 가격·생성 시각·ID 순서로 정렬
→ 상품 99개 반환
```

### 2. 가격순 20개 쿼리를 고정한 기존 인덱스 기준선

같은 쿼리를 고정하고 인덱스에 따른 탐색을 비교한다. 앞의 SQL 변경 단계는 준비 과정이다. 상품의 삭제 조건·동점 정렬을 유지하고 필요한 컬럼을 명시했다. 조회 컬럼을 바꾼 비교와 인덱스를 바꾼 비교는 구분한다.

```sql
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;
```

기존 `(brand_id, deleted_at)` 보조 인덱스의 같은 값 구간에서는 기본키 ID가 순서를 정한다. ID 순서로 조회한 예시는 `(id, price)`가 `(7, 26000) → (107, 96000) → (207, 66000) → (307, 36000) → (407, 6000)`이었다. 가격순이 아니므로 처음 발견한 20개를 바로 반환할 수 없다.

```text
기존 브랜드 인덱스에서 (7, NULL) 구간 시작점 찾기
→ 해당 구간 항목을 따라 읽기
→ 기본키로 이름·가격·생성 시각을 가져오기
→ 후보 99개에서 가격순 상위 20개 선택
→ 20개 반환
```

직접 확인한 계획은 Index lookup 99행 → Sort → Limit 20행이었다. Using index condition은 커버링을 뜻하지 않는다. 이름·가격·생성 시각은 기존 인덱스에 없으므로 일반적인 보조 인덱스 접근에서 실제 행 조회가 필요하다. Handler는 이 InnoDB 내부 기본키 조회를 별도 횟수로 보여주지 않는다. 트리 높이와 실제 페이지 방문은 이번에 측정하지 않았다.

[실행 SQL](../../scripts/sql/week4-03-fixed-product-page.sql)에 인덱스 키 순서를 이해하는 보조 조회도 넣었다. 보조 조회에만 FORCE INDEX와 ORDER BY id를 사용하며 고정 측정 SQL에는 힌트가 없다. 물리 Leaf 페이지를 덤프한 것은 아니다. 새 인덱스는 아직 추가하지 않았으며, 다음에는 동일 쿼리에서 인덱스 후보를 검토한다.

