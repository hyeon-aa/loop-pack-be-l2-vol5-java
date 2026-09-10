# 주문 쿠폰 할인 계약 설계

## 기준 문장

구매자는 주문 확정 전에 보유 쿠폰을 적용할 수 있고, 확정된 할인 금액은 나중에 바뀌지 않아야 한다.

---

## 0단계 — 기존 동작을 읽고 예상 상태를 적는다

### 기존 테스트(`ExampleV1ApiE2ETest`)의 세 경우

| 케이스 | 입력 | 확인된 상태 |
|---|---|---|
| 존재하는 ID | 저장된 exampleId | 2xx, data 필드(id/name/description) 일치 |
| 숫자가 아닌 ID | "나나" | 400 BAD_REQUEST |
| 존재하지 않는 ID | -1 | 404 NOT_FOUND |

> 실행 결과: `./gradlew :apps:commerce-api:test --tests '*ExampleV1ApiE2ETest'` → BUILD SUCCESSFUL, 3개 테스트 모두 통과.

### 네 입력 관찰 테스트(`ContractClassificationTest`)의 상태

| 가설 | HTTP Status | meta.result | errorCode | message | data 유무 |
|---|---|---|---|---|---|
| 존재하는 숫자 ID | 200 | SUCCESS | - | - | 있음 |
| abc | 400 | FAIL | Bad Request | 요청 파라미터 'exampleId' (타입: Long)의 값 'abc'이(가) 잘못되었습니다. | 없음 |
| 존재하지 않는 숫자 ID | 404 | FAIL | Not Found | [id = 999999] 예시를 찾을 수 없습니다. | 없음 |
| 미매핑 URL | 404 | FAIL | Not Found | 존재하지 않는 요청입니다. | 없음 |

> `ApiControllerAdvice`의 `handleNotFound(NoResourceFoundException)`과 `CoreException` 처리 경로를 보면, "존재하지 않는 ID"와 "미매핑 URL"은 원인은 다르지만 같은 404/NOT_FOUND 형태로 응답됨.
> 실행 결과: `ContractClassificationTest`에 4개 케이스 각각 HTTP status, meta.result, error code, data 유무를 assertion으로 작성 → `./gradlew :apps:commerce-api:test --tests 'com.loopers.interfaces.api.ContractClassificationTest'` → BUILD SUCCESSFUL, 4개 테스트 모두 통과.

---

## 1단계 — 기준 문장을 사실과 질문으로 나눈다

### 확인된 사실 (제품 약속) — 기준 문장에서 직접 확인됨

| ID | 확인된 사실 |
|---|---|
| PROMISE-001 | 구매자는 주문 확정 전에 보유 쿠폰을 적용할 수 있다 |
| PROMISE-002 | 주문 확정 뒤 할인 금액은 바뀌지 않는다 |

### 실습 조건 — 이번 실습 범위로 주어진 조건

| ID | 실습 조건 |
|---|---|
| CONDITION-001 | 확정 전 한 주문에는 보유 쿠폰 한 장만 적용한다 |
| CONDITION-002 | finalAmount = originalAmount - discountAmount, 0 ≤ discountAmount ≤ originalAmount |
| CONDITION-003 | 같은 주문·쿠폰 재요청은 저장된 결과를 돌려주고 효과를 늘리지 않는다 |
| CONDITION-004 | 만료 여부는 요청 시작 시각으로 판단한다 |

### 제품 질문 (5개) — 모두 정책 확인 필요, 아래 설계 선택이 이 답을 대신하지 않음

| ID | 질문 | 답에 따라 달라지는 것 |
|---|---|---|
| QUESTION-001 | 주문 금액보다 쿠폰 금액이 더 큰 경우, 0으로 처리할지 적용을 막을지 | 사용자 응답 (할인 적용 성공 여부와 최종 결제 금액이 달라짐) |
| QUESTION-002 | 기존 쿠폰을 자동 취소하고 새 쿠폰을 적용하는지, 아니면 두 번째 쿠폰 적용을 거절하고 클라이언트가 명시적으로 교체 요청을 다시 보내게 하는지? | 사용자 응답 (기존 쿠폰이 자동 해제되는지, 새 쿠폰 적용 자체가 거부되는지) |
| QUESTION-003 | 미확정 주문의 할인 금액은 언제 고정되는가? (적용 시점 / 확정 시점 / TTL 후 재검증) | 저장 데이터 (스냅샷 시각·TTL 필드 필요 여부), 사용자 응답 (결제 시점에 금액이 바뀔 수 있는지, DECISION-002 참고) |
| QUESTION-004 | 운영자가 다른 사람의 쿠폰을 등록하거나, 다른 사람 주문에 쿠폰을 적용할 수 있나? (예: 이벤트 전 주문 고객 일괄 할인) | 권한 (운영자가 구매자와 동일한 제약을 받는지, 별도 권한을 갖는지) |
| QUESTION-005 | 원금(originalAmount)은 상품 가격의 합계인가, 아니면 상품 할인 등 다른 할인이 이미 반영된 금액인가? | 저장 데이터·사용자 응답 (할인 계산의 기준 금액이 달라지고, 그에 따라 실제 할인액의 상한과 최종 금액이 달라짐) |
