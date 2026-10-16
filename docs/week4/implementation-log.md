# 4주차 구현 기록

## 준비와 DB 초기화

- 별도 측정용 MySQL: 3307/loopers_benchmark, MySQL 8.0.46·REPEATABLE READ. seed는 상품 10,000개·브랜드 100개·사용자 200명·좋아요 62,756건이다.
- 사용자 요청으로 이번에 추가한 idx_product_brand_price·idx_product_like_product만 삭제했다. 상품·좋아요 행 수와 기존 브랜드 인덱스·유니크 인덱스·기본키 유지 여부를 확인했다. [상태 변경 원본 · index-reset.json](measurements/index-results.json).
- 코드의 DB 정렬·페이지 구현과 인덱스 선언은 남아 있다. benchmark는 validate로 시작하며 실험 인덱스를 자동 재생성하지 않는다.
- 최초 API 기준선은 [baseline.json](measurements/index-results.json)에 보존한다. 현재 아래 SQL 측정과 서로 다른 범위의 기록이며 직접 시간 비교에 사용하지 않는다.

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

