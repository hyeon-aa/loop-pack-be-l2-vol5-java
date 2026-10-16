# 4주차 구현 기록

## 준비와 DB 초기화

- 별도 측정용 MySQL: 3307/loopers_benchmark, MySQL 8.0.46·REPEATABLE READ. seed는 상품 10,000개·브랜드 100개·사용자 200명·좋아요 62,756건이다.
- 사용자 요청으로 이번에 추가한 idx_product_brand_price·idx_product_like_product만 삭제했다. 상품·좋아요 행 수와 기존 브랜드 인덱스·유니크 인덱스·기본키 유지 여부를 확인했다. [상태 변경 원본 · index-reset.json](measurements/index-results.json).
- 코드의 DB 정렬·페이지 구현과 인덱스 선언은 남아 있다. benchmark는 validate로 시작하며 실험 인덱스를 자동 재생성하지 않는다.
- 최초 API 기준선은 [baseline.json](measurements/index-results.json)에 보존한다. 현재 아래 SQL 측정과 서로 다른 범위의 기록이며 직접 시간 비교에 사용하지 않는다.

