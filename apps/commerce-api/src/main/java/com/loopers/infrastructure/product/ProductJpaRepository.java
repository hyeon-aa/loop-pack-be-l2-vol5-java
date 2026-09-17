package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    boolean existsByBrandIdAndDeletedAtIsNull(Long brandId);

    List<ProductModel> findByDeletedAtIsNull();

    List<ProductModel> findByIdInAndDeletedAtIsNull(Collection<Long> ids);
}
