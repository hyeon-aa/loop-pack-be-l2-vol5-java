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

## 3. 같은 쿼리 + 가격 정렬용 복합 인덱스

측정 DB에 scripts/sql/week4-product-price-index.sql의 CREATE INDEX·ANALYZE TABLE을 실행했다. 기존 브랜드 인덱스는 유지했다. [직접 확인 SQL](../../scripts/sql/week4-05-price-index.sql), [결과 원본 · fixed-page-price-index.json](measurements/index-results.json).

| 확인                   | 기존 브랜드 인덱스 | 가격 정렬용 복합 인덱스 |
| ---------------------- | -----------------: | ----------------------: |
| 상품 접근 실제 rows    |                 99 |                      20 |
| Sort                   |               있음 |                    없음 |
| 반환 상품              |                 20 |                      20 |
| Handler_read_key 증가  |                  1 |                       1 |
| Handler_read_next 증가 |                 99 |                      19 |
| 서버 SQL 중앙값        |            0.170ms |                 0.104ms |

측정 SQL은 이전과 정확히 같고 인덱스 힌트 없이 새 인덱스가 선택됐다. 일반 EXPLAIN 예상 rows는 여전히 99이지만 실제 접근은 20행이었다. LIMIT에 도달해 일찍 종료했기 때문이다. 가격 복합 인덱스는 Using filesort가 사라지고 Using index condition만 나타났으며 커버링 조회는 아니다.

준비 10회·측정 30회와 별도 Handler 1회, 총 41회에서 seed 기대 ID·브랜드·이름·가격·생성 시각·순서가 일치했다. 이전 기준선의 20행과 모든 컬럼·순서도 같았다. Handler는 같은 연결에서 일반 SELECT의 전후 차이를 구했고 ANALYZE는 별도 실행했다. key 1은 시작점을 찾는 요청, next 19는 다음 항목을 읽는 요청으로 총 20개를 반환하는 흐름과 맞는다. 기본키 재조회와 실제 페이지 방문 횟수를 이 값으로 세지 않는다.

키 순서 확인용 보조 조회에서는 같은 가격 6,000·같은 생성 시각 안에서 ID 7407 → 4407 → 1407, 그다음 더 이전 생성 시각의 9407 → 6407 → 3407이 나타났다. 이는 실제 행 값으로 정렬 키 순서를 확인한 예시이며 Leaf 물리 페이지를 덤프한 기록은 아니다.

기존 결과와 측정 시점이 다르고 추가 후 통계도 갱신했다. 서버 SQL 소표본의 시간 차이를 API 개선율로 해석하지 않는다. 이번에는 DDL·SQL 실험·문서만 변경했고 모델 선언은 이전에 이미 존재해 코드 수정·회귀 재실행은 없었다. 이름을 인덱스에서 얻는 커버링 비교와 상품 쓰기 비용은 미측정이다.

### 3.1 가격까지만 있는 인덱스와 동점 정렬까지 있는 인덱스

scripts/compare-week4-price-tie-index.py로 임시 `idx_product_brand_price_only(brand_id, deleted_at, price)`를 생성하고 ANALYZE TABLE 후 비교했다. SELECT 컬럼·WHERE·ORDER BY·LIMIT은 동일하고 비교에서만 FORCE INDEX로 후보를 분리했다. 힌트 없는 SQL이 두 후보 중 무엇을 선택하는지도 원본에 따로 기록했다. [확인 SQL](../../scripts/sql/week4-04-price-tie-index.sql), [결과 원본 · price-tie-index.json](measurements/index-results.json).

| 확인                     | 가격까지만 | 생성 시각 DESC·ID DESC 포함 |
| ------------------------ | ---------: | --------------------------: |
| 접근 실제 rows           |         99 |                          20 |
| Sort                     |       있음 |                        없음 |
| Handler key 증가         |          1 |                           1 |
| Handler next 증가        |         99 |                          19 |
| SQL 중앙값               |    0.163ms |                     0.102ms |
| 인덱스 할당 페이지       |         20 |                          25 |
| 16KiB 페이지 기준 할당량 |     320KiB |                      400KiB |

동일 세션에서 실행 순서를 교대하며 방식별 준비 10회·측정 30회, 총 80회에서 상품 ID·이름·가격·브랜드·생성 시각·순서가 seed 기대값과 같았다. 별도 Handler 측정의 두 후보 결과도 모든 필드·순서가 같았다. Handler는 일반 SELECT의 전후 차이이며 ANALYZE 실행을 포함하지 않았다.

크기는 mysql.innodb_index_stats의 size 페이지 수와 innodb_page_size로 확인했다. 행의 유효 바이트나 디스크 I/O 수가 아니다. 짧은 인덱스가 작지만 이번 쿼리의 추가 정렬을 제거하지 못했다. 인덱스 열 추가가 정확성에 필수인 것은 아니며 비용 선택의 문제다. 로컬 SQL 시간으로 API 개선율을 주장하지 않는다. 상품 쓰기 성능은 미측정이다.

finally에서 생성한 짧은 후보를 삭제했고 기존 인덱스 이름·컬럼·방향이 유지됐음을 확인했다. 전체 정렬 인덱스는 남아 있고 좋아요 새 인덱스는 여전히 없다. 모델·운영 코드 변경은 없었다.

### 3.2 사용자 변경 상태에서 자동 선택과 가격 후보 강제 사용

현재 상품 인덱스를 먼저 확인했다. 기존 idx_product_brand_deleted와 사용자 생성 idx_product_brand_price_only가 있고 전체 정렬 인덱스는 없었다. 스키마·통계·데이터를 변경하지 않고 자동 선택·가격 후보 강제·브랜드 후보 강제의 계획과 Handler·결과를 비교했다. [원본 · price-only-optimizer-choice.json](measurements/index-results.json).

| 확인                  |                 힌트 없음 |        가격 후보 FORCE INDEX |
| --------------------- | ------------------------: | ---------------------------: |
| 선택한 인덱스         | idx_product_brand_deleted | idx_product_brand_price_only |
| 실제 읽기             |                        99 |                           99 |
| Sort                  |                      있음 |                         있음 |
| Handler key·next 증가 |                      1·99 |                         1·99 |
| SQL 중앙값            |                   0.166ms |                      0.159ms |

자동·가격 강제 비교는 같은 프로파일 세션에서 실행 순서를 교대하고 방식별 준비 10회·측정 30회, 총 80회 모든 반환 컬럼·순서 일치를 확인했다. 별도 브랜드 강제 결과도 같았다. 작은 시간 차이와 표본 범위가 겹쳐 어느 쪽의 고정된 성능 우위로 해석하지 않는다.

실제 상품 SELECT의 optimizer trace를 수집했다. ref 후보 둘은 각각 rows=99·cost=34.65였고 기존 브랜드 인덱스가 chosen=true였다. range 후보 둘도 rows=99·cost=34.91로 표시됐으며 짧은 가격 후보는 chosen=false·cause=cost였다. 새 가격 후보가 예상 비용 우위를 제공하지 않는 점은 확인했지만 동률의 내부 선택 순서까지 확인하지 않았다. 최초 추적 수집은 표시용 SELECT가 상품 쿼리의 trace를 덮어써 유효하지 않았고, 표시 쿼리 없이 재수집한 실제 SELECT trace로 교체했다.

FORCE INDEX는 사용 가능 경로를 지정할 뿐 ORDER BY 전체 순서가 맞도록 인덱스를 바꾸지 않는다. 지금 고정 쿼리에서 짧은 가격 후보의 읽기·정렬 이득은 관찰하지 못했다. 가격 범위 등 다른 쿼리에서의 유용성은 별도 검토다. 사용자 직접 실험 중인 인덱스 구성은 그대로 유지했다.

사용자가 이해한 옵티마이저의 예상 비용 선택과 실제 실행 비교의 차이를 design과 04 SQL에 보강했다. 두 후보의 표시 비용이 같았다는 관찰과 동률 선택 규칙을 확인하지 않았다는 한계를 구분했다. 05 SQL에는 사용자 직접 실험 상태에서 짧은 후보 삭제·전체 정렬 인덱스 재생성 절차를 주석으로 명시했다. 이번 작업은 문서 보강이며 DB 변경·새 측정은 하지 않았다. 사용자의 다음 05 결과는 아직 확인하지 않았다.

### 3.3 MySQL 확장에서 Handler가 key 2·next 118 증가한 원인

사용자가 05 SQL의 일반 SELECT 전후에 관찰한 key 138 → 140·next 692 → 810을 조사했다. 파일의 측정 블록과 확장 실행 이력은 SHOW → SELECT → SHOW였다. 별도 MySQL 클라이언트에서는 SELECT만 실행하면 key 1·next 19 증가였고 SHOW만 반복해도 추가 증가가 없었다. 사용자의 실행 실수로 단정할 수 없었다.

benchmark DB의 general log를 잠깐 켜고 실제 편집기의 상품 조회를 실행했다. 같은 연결에서 원래 LIMIT 20 SELECT 다음에 확장이 `SELECT count(*) count0 FROM (원래 SELECT에서 LIMIT을 제거한 쿼리) temp`를 실행한 것을 확인했다. 화면에도 Total 99가 나타났다. 로그 설정은 원래 값으로 복원했다. [실제 SQL·재현 원본 · handler-extension-probe.json](measurements/index-results.json).

포착한 두 SQL을 한 연결에서 재현하니 key 증가 2·next 증가 118이 정확히 나왔다. 원래 SELECT의 1·19와 COUNT의 1·99가 합쳐진 결과다. 이 118을 원래 상품 페이지 SELECT 하나의 읽기량으로 해석하지 않는다. Handler는 의미가 없는 것이 아니라 측정 구간에 확장의 자동 COUNT가 포함됐던 것이다.

앞서 사용자에게 다른 쿼리를 직접 실행했을 가능성을 제시했지만 실제 원인은 확장의 자동 COUNT였다. 상품 SELECT 하나의 비교에는 확장의 COUNT가 없는 클라이언트/실행기를 쓰며 실제 API의 COUNT 비용은 별도로 포함해 평가한다. 현재 편집기에 저장 충돌 메시지가 있어 조사 중 05 SQL의 사용자 편집 내용은 변경하지 않았다.

05 SQL에 확장의 자동 COUNT와 key 2·next 118 해석, 자동 COUNT 없는 터미널 실행 명령을 추가했다. 별도 week4-05-handler-only.sql을 같은 MySQL 연결에서 실행하도록 분리했다. 해당 명령을 직접 실행해 BEFORE key 0·next 0, AFTER key 1·next 19와 상품 20개를 확인했다. 기존 사용자 실행 계획 원문을 블록 주석으로 보존했다. 인덱스·데이터 변경은 없다.

사용자 요청으로 SQL 번호를 04 가격까지만 있는 후보 → 05 생성 시각·ID 동점 정렬까지 포함하는 순서로 변경했다. 전체 정렬 인덱스의 Handler 전용 파일도 week4-05-handler-only.sql로 변경하고 문서 링크·실행 명령을 갱신했다. 기존 실행 계획·쿼리는 보존했다. 후보 99개를 끝까지 읽는 next 99에는 이후 98개와 끝 확인 요청이 포함됐고, LIMIT으로 멈추는 next 19와 구분한다. 인덱스·데이터·측정값은 변경하지 않았다.

## 4. id DESC만 제외한 후보 비교

`scripts/compare-week4-price-tie-index.py --without-id`로 idx_product_brand_price_time(brand_id, deleted_at, price, created_at DESC)을 임시 생성했다. 사용자 재적용한 05 전체 정렬 인덱스와 같은 쿼리·컬럼·WHERE·ORDER BY·LIMIT을 비교했다. 각 경로는 FORCE INDEX로 분리했고 두 후보가 있을 때 힌트 없는 선택은 05였다. [측정 원본 · price-without-id-index.json](measurements/index-results.json), [확인 SQL](../../scripts/sql/week4-06-implicit-id.sql).

| 확인                  |  id DESC 미명시 | 05의 id DESC 명시 |
| --------------------- | --------------: | ----------------: |
| 실제 접근 rows        |              99 |                20 |
| Sort                  |            있음 |              없음 |
| Handler key·next 증가 |            1·99 |              1·19 |
| 서버 SQL 중앙값       |         0.221ms |           0.137ms |
| 인덱스 할당 통계      | 25페이지·400KiB |   25페이지·400KiB |

방식별 준비 10회·측정 30회, 총 80회 실행 순서를 교대했다. 모든 반환 컬럼·순서가 seed 기대값과 같았다. Handler 측정도 별도 일반 SELECT의 같은 연결 전후 차이로 확인했다. SQL 시간은 로컬 서버 시간이고 HTTP·쓰기 비용을 포함하지 않는다.

id ASC로 바꾼 보조 조회는 same price/time의 1407 → 4407 → 7407을 반환했고 EXPLAIN에서 Using index·filesort 없음이었다. 이는 기본키 자동 포함으로 ID를 얻을 수 있음을 보여주지만 원래 쿼리의 id DESC 요구가 해결됐다는 뜻은 아니다. 보조 조회의 ORDER BY 변경은 원래 쿼리 성능 비교와 구분한다.

finally에서 이번 임시 후보만 삭제하고 기존 인덱스 이름·열·방향이 유지됐음을 확인했다. 모델·운영 코드·상품 데이터 변경은 없으며 ANALYZE TABLE로 통계는 갱신됐다. 05 전체 정렬 인덱스는 유지한다. SQL 파일은 임시 후보가 이미 삭제된 상태에서 재생성 절차와 관찰 결과를 설명한다.

## 5. 이름 포함·제외 커버링 비교

scripts/compare-week4-covering.py로 기존 05 인덱스에서 SELECT의 name만 포함·제외했다. WHERE·ORDER BY·LIMIT은 같고 힌트 없이 같은 인덱스를 선택했다. 준비 포함 총 82회에서 공통 컬럼·순서가 seed 기대값과 같았고 이름 포함 조회의 이름도 확인했다. [원본 · covering-name.json](measurements/index-results.json), [직접 확인 SQL](../../scripts/sql/week4-07-covering.sql).

| 확인                  | 이름 포함             | 이름 제외                |
| --------------------- | --------------------- | ------------------------ |
| EXPLAIN Extra         | Using index condition | Using where; Using index |
| ANALYZE 접근          | Index lookup          | Covering index lookup    |
| 실제 접근 rows        | 20                    | 20                       |
| Sort                  | 없음                  | 없음                     |
| Handler key·next 증가 | 1·19                  | 1·19                     |
| SQL 중앙값            | 0.136ms               | 0.116ms                  |

방식별 준비 10회·측정 30회로 먼저 실행하는 방식을 교대했다. Handler는 자동 COUNT 없는 클라이언트의 별도 SELECT 전후 측정이다. 인덱스·데이터·통계 변경은 없었다. 이름 제외는 반환 컬럼을 줄인 원리 실험이며 API 응답 개선안은 아니다.

### 5.1 이름을 인덱스에 포함하는 대안

사용자 제안으로 이름을 맨 뒤에 포함한 idx_product_brand_price_name을 임시 생성하고 ANALYZE TABLE 후 05와 비교했다. 이름을 포함한 같은 원래 쿼리에서만 비교하며 FORCE INDEX로 경로를 분리했다. 힌트 없는 선택도 원본에 저장했다. [원본 · covering-name-index.json](measurements/index-results.json).

| 확인                  | 05 인덱스    | 이름 포함 인덱스      |
| --------------------- | ------------ | --------------------- |
| ANALYZE 접근          | Index lookup | Covering index lookup |
| 실제 접근 rows·Sort   | 20·없음      | 20·없음               |
| Handler key·next 증가 | 1·19         | 1·19                  |
| SQL 중앙값            | 0.127ms      | 0.110ms               |
| 인덱스 할당량         | 400KiB       | 1,552KiB              |

방식별 준비 10회·측정 30회, 준비 포함 총 82회에서 반환된 전체 필드·순서가 앞선 seed 검증 결과와 같았다. 동일 세션에서 실행 순서를 교대했다. 임시 후보는 finally에서 삭제했고 기존 상품 인덱스가 남았음을 확인했다. 모델·운영 조회 코드는 변경하지 않았다.

시간은 서로 다른 두 실험을 합쳐 비교하지 않는다. 이름 인덱스 비교에는 05 최대 12.93ms·이름 후보 최대 9.83ms의 변동도 있어 작은 중앙값 차이를 안정적인 API 성능 이득으로 주장하지 않는다. 실제 API·상품 쓰기·MVCC 내부 기본키 접근은 측정하지 않았다. 페이지 할당량은 통계 기반이며 정확한 유효 바이트가 아니다. 이름 후보의 공간·관리 비용과 실제 이득을 추가 검토하기 전까지 05를 유지한다.

커버링은 인덱스 탐색을 없애는 것이 아니라 필요한 컬럼을 얻기 위한 실제 행 재조회를 줄인다는 이해를 design에 보강했다. 사용자 질문으로 기존 브랜드 인덱스와 가격 인덱스의 왼쪽 선두 컬럼 중복을 다음 검토 후보로 기록했다. 실제 코드의 브랜드 삭제 대상 ID 조회에는 ORDER BY id가 있어 긴 인덱스의 가격 순서와 다른 점을 확인했다. 이 문서 보강에서 DB 변경·대체 성능 측정은 하지 않았다.

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

