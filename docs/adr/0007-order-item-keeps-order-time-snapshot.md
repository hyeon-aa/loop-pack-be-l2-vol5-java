# ADR 0007: OrderItem은 주문 시점의 상품 정보를 보존한다

Status: Accepted
Date: 2026-09-18

## Context

주문을 생성한 뒤 상품 이름·가격이 바뀌거나 상품이 논리 삭제되어도, 과거 주문의 품목과 결제 금액은 변하면 안 된다. 주문 조회 시 현재 Product를 다시 조회해 정보를 구성할지, 주문 생성 시 필요한 값을 복사할지 결정해야 했다.

## Decision

`OrderItem`에 `productId`, 주문 시점의 `productName`, `unitPrice`, `quantity`를 함께 저장한다. 주문 총액과 확정 결제액은 이 스냅샷을 기준으로 계산한다.

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| 현재 Product를 조회해 주문 정보를 구성 | 저장 데이터가 적다 | 가격 변경·삭제에 따라 과거 주문 이력이 달라진다 | 주문 이력 보존 요구와 맞지 않는다 |
| OrderItem에 주문 시점 값을 복사 | 주문 이력이 독립적으로 유지된다 | 상품 정보가 일부 중복 저장된다 | 선택 |

## Consequences

상품의 이후 변경이나 논리 삭제는 기존 주문의 이름·단가·총액에 영향을 주지 않는다. `OrderItem`은 Order Aggregate 밖에서 직접 변경하지 않는 내부 Entity로 관리한다.

## Evidence

- [설계 규칙 ORD-02, ORD-03, ORD-06](../week2/design.md)
- `apps/commerce-api/src/main/java/com/loopers/domain/order/OrderItem.java`
- `apps/commerce-api/src/test/java/com/loopers/application/order/OrderPersistenceIntegrationTest.java`
