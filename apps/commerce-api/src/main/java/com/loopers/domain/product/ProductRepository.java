package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> find(Long id);

    List<ProductModel> findAllActive();

    List<ProductModel> findActiveByIds(Collection<Long> ids);

    boolean existsActiveByBrandId(Long brandId);
}
