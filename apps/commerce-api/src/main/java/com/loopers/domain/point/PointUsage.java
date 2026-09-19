package com.loopers.domain.point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

@Entity
@Table(name = "point_usage")
public class PointUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointUsageType type;

    @Column(nullable = false)
    private ZonedDateTime occurredAt;

    protected PointUsage() {}

    PointUsage(long amount, PointUsageType type, ZonedDateTime occurredAt) {
        this.amount = amount;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    long getAmount() {
        return amount;
    }
}
