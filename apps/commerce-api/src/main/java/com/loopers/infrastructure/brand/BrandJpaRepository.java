package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BrandJpaRepository extends JpaRepository<BrandModel, Long> {
    boolean existsByNameIgnoreCaseAndDeletedAtIsNull(String name);

    List<BrandModel> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    List<BrandModel> findByDeletedAtIsNull();
}
