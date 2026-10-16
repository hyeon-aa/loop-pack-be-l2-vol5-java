<a id="query-contract"></a>

## 조회 계약과 측정 환경

상품 10,000건에서 SQL 원리를 확인하고, 100,000건을 복사한 실험 DB에서 실제 앱을 비교한다. 원본 DB·개발 DB와 분리한 benchmark MySQL 8.0.46·InnoDB·REPEATABLE READ를 사용한다. 기존 상품 인덱스와 상품별 좋아요 카운터를 유지한다. 설정은 [setup.md](setup.md)를 따른다.

가격순은 `price ASC, createdAt DESC, id DESC`다. 브랜드 필터·삭제 상품/브랜드 제외·상품 표시 필드·현재 좋아요 수·page/size 검증을 지킨다. 좋아요순은 전체 후보의 좋아요 수로 정렬한 뒤 페이지를 선택한다. 빈 페이지는 빈 items로 반환하고 totalCount·hasNext는 제공하지 않는다.

총상품수 제외 결정의 이유와 응답 영향은 [ADR 0022](../adr/0022-product-list-without-total-count.md#이유와-결정)를 따른다.

