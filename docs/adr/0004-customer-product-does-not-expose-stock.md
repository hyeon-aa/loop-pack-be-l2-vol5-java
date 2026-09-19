# ADR 0004: 고객 상품 응답은 실제 재고를 노출하지 않는다

Status: Accepted
Date: 2026-09-17

## Context

관리자는 재고를 관리해야 하지만 고객에게 실제 수량을 공개할 필요는 없다.

## Decision

고객 Product 응답은 ID·브랜드 ID·이름·가격을 반환하고 재고 수량을 포함하지 않는다. 관리자 Product 응답에는 재고를 포함한다.

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| 고객·관리자 모두 실제 재고 공개 | DTO가 하나다 | 운영 재고가 고객에게 노출된다 | 고객 요구와 맞지 않는다 |
| 응답 DTO 분리 | 각 API 수신자에게 필요한 정보만 제공 | DTO가 하나 더 필요하다 | 선택 |

## Consequences

ProductInfo는 application 내부의 공통 정보이며, customer/admin interfaces DTO가 각각 필요한 필드만 선택한다.

## Evidence

- `apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/ProductV1Dto.java`
- `apps/commerce-api/src/main/java/com/loopers/interfaces/api/admin/product/AdminProductV1Dto.java`
