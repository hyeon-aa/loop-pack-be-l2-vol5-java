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
