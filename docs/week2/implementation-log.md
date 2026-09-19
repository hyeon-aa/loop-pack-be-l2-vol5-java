# 구현·검증 기록

## 최종 검증과 통합 테스트 보강

- Docker Engine 29와 Testcontainers 1.20.6의 Unix socket `Host` 헤더 호환성 문제는 `scripts/fix-docker-testcontainers.sh`로 Colima TCP 터널을 열어 해결했다. 프로젝트의 build/dependency 파일은 이 문제 때문에 변경하지 않았다.
- `OrderItem`, `PointGrant`, `PointUsage`는 각 Aggregate가 소유하는 자식이므로 조인 테이블 대신 `order_id`, `point_balance_id`, `point_grant_id` 외래 키로 저장한다. 자동 조인 테이블이 테스트 정리 대상에서 누락돼 관계가 남는 문제를 실제 DB 통합 테스트로 발견했다.
- `PointFacade.getBalance`는 만료 기록과 잔액을 저장할 수 있어야 하므로 읽기 전용 트랜잭션이 아니다. 만료된 적립금은 잔액 조회에서 한 번만 소멸·저장되는지 재조회로 검증했다.
- 주문 확정 실패 통합 테스트는 만료 반영 뒤 잔액이 부족하면 주문 상태·재고·저장 포인트 잔액이 모두 이전 상태로 유지되는지 확인한다.
- 관리자 접근 통합 테스트는 실제 MockMvc → controller → application → repository 흐름에서 `X-USER-ROLE: ADMIN`만 허용하고 USER·헤더 없음은 403인지 확인한다.
- `server.address=127.0.0.1`을 추가해 로컬 실습 서버를 루프백 주소로 제한했다.

최종 실행:

```bash
source ./scripts/fix-docker-testcontainers.sh
./gradlew :apps:commerce-api:check
```

결과: **117건, 실패 0, 오류 0, skip 0. Checkstyle·ArchUnit 통과.**

## Brand 모델의 생성 규칙

- 사용자 승인: starter처럼 JPA 모델 사용, 설명 없음은 null, 내용 있는 설명 보존, Unicode 코드 포인트 길이 계산.
- 추가: `BrandModel`, `BrandModelTest`.
- 추가: `BrandRepository`, `BrandService`, `BrandInfo`, `BrandFacade`, JPA repository/구현체와 `BrandServiceTest`.
- 이름은 앞뒤 공백 제거 후 1~100자, 설명은 최대 1,000자. 공백 설명은 null. 영문 대소문자와 내부 공백은 표시 이름에 보존한다.
- `IllegalArgumentException`으로 잘못된 모델 입력을 거절한다. HTTP 오류 코드는 아직 결정하지 않았으므로 기존 HTTP 상태를 가진 ErrorType과 연결하지 않았다. API 연결 단계에서 오류 계약/변환을 확정해야 한다.
- 활성 브랜드 이름 중복은 Repository/application 연결 단계에서 검증한다. 이번 모델 단위 테스트가 DB 중복/정렬 규칙까지 검증한 것은 아니다.
- `BrandService`가 모델을 만든 뒤 활성 이름 중복을 조회하고, 중복이면 `CONFLICT`, 없으면 저장하도록 연결했다. 조회 시 삭제된 Brand는 `NOT_FOUND`로 처리한다.
- JPA annotation은 추가했지만 저장·재조회나 DB 문자셋/정렬 규칙은 아직 검증하지 않았다. 기존 BaseEntity의 delete/restore 상속도 이번에 브랜드 삭제/복원 유스케이스를 구현했다는 뜻은 아니다.

실행 명령: `./gradlew :apps:commerce-api:test --tests '*BrandModelTest'`

결과: **BUILD SUCCESSFUL, 17건, 실패 0, 오류 0, skip 0.**

추가 실행: `./gradlew :apps:commerce-api:test --tests '*Brand*Test'` → **BUILD SUCCESSFUL, 21건, 실패 0, 오류 0, skip 0.**

주의: 현재 환경 Java 17, 생성된 BrandModel 클래스 major version 61(Java 17)이다. 과제가 요구하는 JDK 21 검증은 미완료다. 루트 toolchain 설정이 하위 모듈의 Java 21 실행까지 보장하지 않았다. JDK 21 설치/선택과 해당 모듈 toolchain 적용을 별도 작업으로 해결해야 한다.

이번에는 의도한 실패를 먼저 실행하지 않았으므로 TDD Red→Green 완료 사례로 기록하지 않는다. 대표 TDD는 계획대로 Stock 단계에서 수행한다. Checkstyle·ArchUnit·AGENTS.md 설정은 아직 미완료이며, 관련 검사를 통과했다고 보고하지 않는다. 커밋은 수행하지 않았다.

## Brand HTTP 연결과 JDK 21 검증

- Homebrew OpenJDK 21(`21.0.12.1`)을 설치했다. 시스템 전역 Java 등록은 하지 않았고, 검증 시 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`을 지정했다.
- 고객 브랜드 상세 `GET /api/v1/brands/{brandId}`는 `200 OK`, 관리자 브랜드 등록 `POST /api-admin/v1/brands`는 `201 Created`와 기존 `ApiResponse` 형식으로 응답한다.
- 등록 요청은 `{ "name": "...", "description": "..." }`이다. 현재 관리자 인증 기반이 없으므로 이 단계는 관리자 경로 계약만 추가했으며, 접근 제어는 관리자 기능 구현 단계에서 연결한다.
- `IllegalArgumentException`은 API 계층에서 `400 BAD_REQUEST`로 변환한다.
- `BrandV1ControllerTest`에서 두 엔드포인트의 상태 코드·응답 형식·facade 전달값을 검증한다.

실행 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \\
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \\
./gradlew :apps:commerce-api:test --tests '*Brand*Test'
```

결과: **BUILD SUCCESSFUL, 23건, 실패 0, 오류 0, skip 0.**

## 코드 스타일·계층 의존 검사

- Checkstyle 10.26.1을 모든 Java 하위 모듈의 `check` 작업에 연결했다. 현재 규칙은 와일드카드 import와 미사용 import 금지다.
- ArchUnit 1.5.0을 commerce-api 테스트 의존성에 추가하고, `interfaces → application → domain ← infrastructure` 계층 의존을 `LayeredArchitectureTest`로 검사한다. 테스트 소스는 검사 대상에서 제외한다.
- 검증 명령: `./gradlew :apps:commerce-api:test --tests '*ArchitectureTest' :apps:commerce-api:check`
- 결과: **BUILD SUCCESSFUL.** `LayeredArchitectureTest` 1건은 실패·오류·skip 없이 통과했고, `checkstyleMain`과 `checkstyleTest`도 통과했다.

## Product 등록·상세 조회

- 사용자 승인: 상품명은 앞뒤 공백 제거 후 1~100 Unicode 코드 포인트, 가격은 양수 `long`, 초기 재고는 필수 0 이상 `int`다.
- `ProductModel`은 `brandId`, 이름, 가격과 Product 내부 `Stock` 값을 보관한다. `Stock`은 이 단계에서 초기 수량의 0 이상만 검증한다. 차감과 최종 수량 변경은 다음 TDD 단계에서 추가한다.
- `ProductService`는 새 Product를 저장하기 전에 활성 Brand 조회를 수행한다. 고객 상세 조회에서는 논리 삭제된 Product를 제외한다.
- 고객 상세 `GET /api/v1/products/{productId}`는 `200 OK`, 관리자 등록 `POST /api-admin/v1/products`는 `201 Created`를 반환한다. 목록 API는 페이지·정렬 계약(Q05)이 정해진 뒤 구현한다.

실행 명령: `./gradlew :apps:commerce-api:test --tests '*Product*Test'`

결과: **BUILD SUCCESSFUL, 13건, 실패 0, 오류 0, skip 0.** 이어서 `:apps:commerce-api:check`와 `LayeredArchitectureTest`도 통과했다.

## Stock 차감 TDD

- Red: `StockTest`에 정상 차감, 0·음수 차감 거절, 보유량 초과 거절과 실패 후 원래 수량 유지를 작성했다. 최소 스텁을 실행한 결과 5건 중 4건이 기대한 업무 규칙 때문에 실패했다. 컴파일이나 환경 오류가 아니었다.
- Green: `Stock.decrease`는 양수 여부와 보유 수량을 검증하고, 통과하면 새 `Stock` 값을 반환한다. 실패하면 기존 `Stock`을 변경하지 않는다.
- Refactor: `ProductModel.decreaseStock`이 내부 `Stock`의 반환값으로 재고를 교체하도록 했다. Product 수준에서도 정상 차감과 실패 후 수량 보존을 테스트했다.

실행 명령:

```bash
./gradlew :apps:commerce-api:test --tests '*StockTest'
./gradlew :apps:commerce-api:test --tests '*Product*Test'
./gradlew :apps:commerce-api:check
```

결과: Red는 **5건 중 4건 실패**, Green의 `StockTest`는 **5건, 실패 0, 오류 0, skip 0**, Product 재검증은 **15건, 실패 0, 오류 0, skip 0**이었다. `checkstyleMain`과 `checkstyleTest`도 통과했다.

## Product 책임 경계와 재고 노출

- 사용자 결정: 고객 상품 상세에는 실제 재고 수량을 노출하지 않는다. 고객 DTO는 ID·브랜드 ID·이름·가격만 반환하며, 관리자 등록 응답은 재고 수량을 포함한다.
- Brand 활성 여부 확인은 `ProductService`에서 `ProductFacade`로 옮겼다. Facade가 Brand와 Product 두 Aggregate의 협력을 조율하고, ProductService는 Product 생성·저장·조회만 담당한다.
- `ProductFacadeTest`는 활성 Brand 확인이 Product 생성보다 먼저 호출되는지 검증한다.

검증 결과: `*Product*Test` **16건**, `*ArchitectureTest` **1건**, 실패·오류·skip 없이 통과했다. `:apps:commerce-api:check`도 통과했다.

## 관리자 재고 최종 수량 설정

- 사용자 승인: `PUT /api-admin/v1/products/{productId}/stock`에 `{ "quantity": 20 }`을 보내면 `200 OK`와 변경된 관리자용 상품 정보를 반환한다.
- `Stock.changeTo`는 0 이상 최종 수량을 검증해 새 `Stock`을 반환한다. Product는 해당 값을 내부 재고로 교체한다.
- `ProductService.changeStock`은 활성 Product만 조회·변경·저장한다. 삭제 Product 정책은 논리 삭제 기능을 추가할 때 같은 조회 경로로 적용된다.
- 고객 상세 응답은 재고 수량을 포함하지 않는다.

검증 결과: `*Product*Test` **19건**, `*StockTest` **6건**, 실패·오류·skip 없이 통과했다. `:apps:commerce-api:check`도 통과했다.

## 상품 수정

- 사용자 승인: `PUT /api-admin/v1/products/{productId}`는 브랜드를 변경하지 않고 `{ "name": "...", "price": 120000 }`으로 이름·가격을 모두 수정하며 `200 OK`를 반환한다.
- Product는 모든 새 입력을 검증한 뒤에만 이름·가격을 반영한다. 따라서 가격 등 입력이 잘못되면 기존 이름·가격·브랜드·재고가 부분 변경되지 않는다.
- Facade는 HTTP와 도메인 사이의 use case 흐름을 조율하고, Product 규칙은 ProductModel과 Stock에 남긴다.

검증 결과: `*Product*Test` **23건**, 실패·오류·skip 없이 통과했고 `:apps:commerce-api:check`도 통과했다.

## 상품 논리 삭제

- 사용자 승인: 활성 상품 삭제는 `DELETE /api-admin/v1/products/{productId}`로 수행하고 `204 No Content`를 반환한다. 삭제된 상품을 다시 삭제하면 `404 Not Found`다.
- `ProductModel.markDeleted`는 `deletedAt`만 기록한다. 재고 수량은 보존한다.
- ProductService의 `get`은 활성 상품만 반환하므로 고객 상세·상품 수정·재고 변경과 재삭제가 삭제 상품을 자동으로 제외한다.

검증 결과: `*Product*Test` **27건**, 실패·오류·skip 없이 통과했고 `:apps:commerce-api:check`도 통과했다.

## 브랜드 논리 삭제

- 사용자 승인: 활성 상품이 하나라도 있으면 브랜드 삭제는 `409 Conflict`로 거절한다. 활성 상품이 없으면 `DELETE /api-admin/v1/brands/{brandId}`가 `204 No Content`로 브랜드를 논리 삭제한다.
- BrandFacade가 ProductRepository로 활성 소속 상품 존재를 확인하고 BrandService에 상태 변경을 요청한다. BrandService는 Brand 상태 변경과 저장만 맡는다.

검증 결과: `*Brand*Test` **28건**, 실패·오류·skip 없이 통과했고 `:apps:commerce-api:check`도 통과했다.

## User 존재 검증 기반

- Like·Point·Order에서 재사용할 최소 `UserModel`, `UserRepository`, `UserService`를 추가했다. 사용자 등록·프로필 API는 이번 범위에 포함하지 않으며 테스트 fixture에서 사용자를 만든다.
- `UserService.get`은 존재하지 않거나 논리 삭제된 사용자를 `404 Not Found`로 처리한다.

검증 결과: `*User*Test` **2건**, 실패·오류·skip 없이 통과했고 `:apps:commerce-api:check`도 통과했다.
