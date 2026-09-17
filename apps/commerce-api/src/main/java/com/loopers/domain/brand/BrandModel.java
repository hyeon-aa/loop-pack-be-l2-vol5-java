package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class BrandModel extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 1000)
    private String description;

    protected BrandModel() {}

    public BrandModel(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("브랜드 이름은 비어있을 수 없습니다.");
        }
        String normalizedName = name.strip();
        if (lengthOf(normalizedName) > 100) {
            throw new IllegalArgumentException("브랜드 이름은 100자 이하여야 합니다.");
        }
        String normalizedDescription = description == null || description.isBlank() ? null : description;
        if (normalizedDescription != null && lengthOf(normalizedDescription) > 1000) {
            throw new IllegalArgumentException("브랜드 설명은 1,000자 이하여야 합니다.");
        }
        this.name = normalizedName;
        this.description = normalizedDescription;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void markDeleted() {
        delete();
    }

    public void update(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("브랜드 이름은 비어있을 수 없습니다.");
        }
        String normalizedName = name.strip();
        if (lengthOf(normalizedName) > 100) {
            throw new IllegalArgumentException("브랜드 이름은 100자 이하여야 합니다.");
        }
        String normalizedDescription = description == null || description.isBlank() ? null : description;
        if (normalizedDescription != null && lengthOf(normalizedDescription) > 1000) {
            throw new IllegalArgumentException("브랜드 설명은 1,000자 이하여야 합니다.");
        }
        this.name = normalizedName;
        this.description = normalizedDescription;
    }

    private static int lengthOf(String value) {
        return value.codePointCount(0, value.length());
    }
}
