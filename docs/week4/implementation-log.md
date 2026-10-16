# 4주차 구현 기록

## 준비와 DB 초기화

- 별도 측정용 MySQL: 3307/loopers_benchmark, MySQL 8.0.46·REPEATABLE READ. seed는 상품 10,000개·브랜드 100개·사용자 200명·좋아요 62,756건이다.
- 사용자 요청으로 이번에 추가한 idx_product_brand_price·idx_product_like_product만 삭제했다. 상품·좋아요 행 수와 기존 브랜드 인덱스·유니크 인덱스·기본키 유지 여부를 확인했다. [상태 변경 원본 · index-reset.json](measurements/index-results.json).
- 코드의 DB 정렬·페이지 구현과 인덱스 선언은 남아 있다. benchmark는 validate로 시작하며 실험 인덱스를 자동 재생성하지 않는다.
- 최초 API 기준선은 [baseline.json](measurements/index-results.json)에 보존한다. 현재 아래 SQL 측정과 서로 다른 범위의 기록이며 직접 시간 비교에 사용하지 않는다.

## 준비 측정: 전체 조회와 브랜드 7 조건 조회

[실행 SQL](../../scripts/sql/week4-01-brand-filter.sql), [측정 원본 · brand-filter-reset.json](measurements/index-results.json).

| 확인                       | 전체 미삭제 상품 조회 |     브랜드 7 미삭제 상품 조회 |
| -------------------------- | --------------------: | ----------------------------: |
| EXPLAIN 접근               |  ALL·인덱스 선택 없음 | ref·idx_product_brand_deleted |
| 상품 접근 노드 실제 rows   |                10,000 |                            99 |
| 반환 상품                  |                 9,699 |                            99 |
| Handler_read_key 차분      |                     1 |                             1 |
| Handler_read_next 차분     |                     0 |                            99 |
| Handler_read_rnd_next 차분 |                10,001 |                             0 |
| 서버 SQL 중앙값            |               4.550ms |                       0.356ms |

방식별 준비 10회·측정 30회로 먼저 실행하는 방식을 교대했다. 준비 포함 80회에서 상품 ID를 독립적인 seed 기대값과 비교했다. 전체 결과에서 브랜드 7을 고른 모든 상품 컬럼이 브랜드 조건 SQL의 결과와 같았다. 스키마·데이터는 변경하지 않았다.

반환 상품은 SQL이 애플리케이션에 내보낸 행 수다. 전체 스캔의 Handler_read_rnd_next 10,001은 끝 확인을 포함한 요청이며 상품 10,001개나 디스크 읽기 횟수를 뜻하지 않는다. 이 비교에는 가격 정렬·LIMIT·좋아요·HTTP가 없다.

## 1. ORDER BY만 추가

[실행 SQL](../../scripts/sql/week4-02-price-sort.sql), [측정 원본 · price-sort-reset.json](measurements/index-results.json).

| 확인                              | 브랜드 7 조회 | ORDER BY 추가 |
| --------------------------------- | ------------: | ------------: |
| 기존 브랜드 인덱스 접근 실제 rows |            99 |            99 |
| 반환 상품                         |            99 |            99 |
| Sort                              |          없음 |          있음 |
| Handler_read_key 차분             |             1 |             1 |
| Handler_read_next 차분            |            99 |            99 |
| 서버 SQL 중앙값                   |       0.345ms |       0.378ms |

방식별 준비 10회·측정 30회로 먼저 실행하는 방식을 교대했다. 준비 포함 80회 모두 상품 컬럼 집합이 같았다. 정렬 SQL의 ID 순서는 seed에서 독립적으로 계산한 가격 ASC·생성 시각 DESC·ID DESC 순서와 일치했다. LIMIT·좋아요·새 인덱스와 애플리케이션 코드는 변경하지 않았다.

EXPLAIN·EXPLAIN ANALYZE와 같은 연결의 Handler 전후 차이를 저장했다. 기존 인덱스로 99개를 읽는 작업은 같고 그 뒤 Sort가 추가됐다. Handler_read 값이 같아도 정렬 작업이 없는 것은 아니다. 이 카운터는 정렬 비교·CPU 작업을 직접 세지 않는다.

위 시간은 SHOW PROFILES의 서버 SELECT 시간이며 클라이언트·Java·HTTP 비용을 포함하지 않는다. 현재 통계와 로컬 소표본의 결과로, 작은 시간 차이를 API 개선율로 해석하지 않는다. 이번 문서 정리는 새로운 코드 변경이나 추가 테스트 실행이 아니다.

## 2. 고정 쿼리: 기존 브랜드 인덱스로 가격순 20개 조회

[실행 SQL](../../scripts/sql/week4-03-fixed-product-page.sql), [측정 원본 · fixed-page-brand-index.json](measurements/index-results.json), 실행기 `scripts/measure-week4-fixed-product-page.py`. 기존 브랜드 인덱스를 유지하고 힌트 없이 실행했다. 데이터·인덱스·통계는 변경하지 않았다.

| 확인                      | 결과                                            |
| ------------------------- | ----------------------------------------------- |
| EXPLAIN                   | ref·idx_product_brand_deleted·예상 rows 99      |
| Extra                     | Using index condition; Using filesort           |
| ANALYZE                   | Index lookup 99행 → Sort 상위 20행 → Limit 20행 |
| Handler_read_key 차분     | 1                                               |
| Handler_read_next 차분    | 99                                              |
| 그 밖의 Handler_read 차분 | 모두 0                                          |
| SQL 반환                  | 20개                                            |
| 서버 SQL 중앙값           | 0.170ms                                         |

준비 10회·측정 30회와 별도 Handler 측정 1회, 총 41회에서 상품 ID·브랜드 ID·이름·가격·생성 시각·순서를 독립적인 seed 기대값과 확인했다. 최초 실행은 날짜 표시의 소수점 형식 차이로 문자열 assertion에서 중단됐고 성공으로 기록하지 않았다. 날짜 값으로 정규화한 재실행 결과만 기록했다.

Handler는 일반 SELECT 하나의 같은 연결 직전·직후 차이이며 EXPLAIN ANALYZE는 따로 실행했다. Using filesort는 인덱스가 가격순 정렬을 해결하지 못해 별도 정렬했다는 뜻이지 디스크 정렬을 증명하지 않는다. Sort는 LIMIT을 고려해 상위 20개를 유지할 수 있으나 입력 후보 99개는 확인해야 했다. 서버 시간은 HTTP 시간과 구분한다.

기존 인덱스 순서를 이해하기 위해 브랜드 7·미삭제 구간을 ID 순으로 10개 조회했다. 가격은 26,000·96,000·66,000·36,000·6,000·76,000·46,000·16,000·86,000·56,000으로 가격순이 아니었다. 이 보조 쿼리에만 FORCE INDEX를 사용했다. 실제 페이지 덤프나 트리 높이 측정은 아니다. 읽는 과정의 설명과 실제 측정값을 design에 연결했다.

고정 쿼리 SQL 파일에 Handler 항목·같은 연결의 전후 차이 계산·측정 한계를 주석으로 추가했다. 직전 단계와 key 1·next 99가 같은 이유는 LIMIT이 반환 수만 줄이고 읽는 후보를 줄이지 않았기 때문이다. 사용자가 저장한 실행 계획 원문은 실행 시 SQL 오류가 나지 않도록 블록 주석으로 감싸 보존했다. 이번 변경은 설명 보강이며 새 측정은 하지 않았다.

## 17. 브랜드 중복 조회 제거

`ProductQueryService.getList()`가 `brandService.get(brandId)`로 브랜드를 확인한 뒤, `enrich()` 안에서 `brandService.getAllActiveByIds(...)`로 같은 브랜드를 다시 조회하고 있었다. 9절에서 이미 업무 SELECT 5개를 나열했지만("브랜드 확인·COUNT·상품 페이지·브랜드 정보·좋아요 집계") 그중 "브랜드 확인"과 "브랜드 정보"가 중복이라는 점은 그때 짚지 않았다.

`enrich(Collection<ProductModel>, BrandModel)` 오버로드를 추가해 이미 조회한 브랜드를 재사용하도록 고쳤다. `get(productId)`·`getActiveByIdsInOrder(...)`는 변경하지 않았다 — 두 메서드는 brandId가 미리 정해지지 않아 기존 일반 조회(`enrich(products)`, 내부에서 `getAllActiveByIds`)가 그대로 필요하다.

**회귀 확인:** `:apps:commerce-api:test --tests '*ProductQueryServiceTest*' --tests '*ProductBrandFilterIntegrationTest*' --tests '*ProductFacadeTest*' --tests '*ProductV1ControllerTest*'`와 전체 `:apps:commerce-api:check` 모두 통과(267건, 실패·오류·skip 0, Checkstyle·ArchUnit 포함). `ProductBrandFilterIntegrationTest.preservesBrandAndPageValidation`(존재하지 않는 브랜드 ID로 조회하면 에러)이 그대로 통과해, 브랜드 존재 검증 동작은 바뀌지 않았음을 확인했다.

**실제 API 재측정:** `scripts/measure-week4-100k-api.py`(같은 100k·hotspot DB, 새 jar)로 다시 측정했다. 업무 SELECT가 4개로 줄었음을 확인하고 스크립트의 `assert ... == 5`도 `== 4`로 함께 갱신했다. 응답의 브랜드 정보(`"brand":{"id":7,"name":"W4 Brand 007"}`)와 `likeCount`(일반 DB 2, hotspot DB 50,002)는 이전 측정과 동일하게 정확했다.

| DB                             | HTTP 중앙값 |     p95 | SELECT 수 |
| ------------------------------ | ----------: | ------: | --------: |
| loopers_benchmark_100k(일반)   |     11.76ms | 12.73ms |         4 |
| loopers_benchmark_100k_hotspot |     11.61ms | 12.72ms |         4 |

[측정 · 100k-brand-dedup-api.json](measurements/index-results.json). 직전 측정([100k-counter-api.json](measurements/like-counter-results.json), 중앙값 17.84ms·13.09ms, SELECT 5개)보다 낮게 나왔지만, 별도 JVM·소표본 간 비교이고 브랜드 PK 단건 조회 자체가 이미 저렴해서 이 차이 전부를 이 변경의 효과로 단정하지 않는다. SQL 개수를 줄였다는 사실과 체감 가능한 지연 개선을 구분한다.

