package com.loopers.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_item")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false, length = 100)
    private String productName;
    @Column(nullable = false)
    private long unitPrice;
    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {}

    public OrderItem(Long productId, String productName, long unitPrice, int quantity) {
        if (productId == null || productId <= 0 || quantity <= 0 || unitPrice <= 0) {
            throw new IllegalArgumentException("주문 품목이 올바르지 않습니다.");
        }
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public long getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public long getTotalPrice() { return Math.multiplyExact(unitPrice, quantity); }
}
