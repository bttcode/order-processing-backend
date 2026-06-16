package com.bowt.backend.orderprocessing.infrastructure.persistence.adapter;

import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ProductJpaAdapter implements ProductRepository {

    private final JpaProductRepository jpaRepo;
    private final ProductMapper mapper;

    @Override
    public Optional<Product> findById(String businessId) {
        return jpaRepo.findByBusinessId(businessId).map(mapper::toDomain);
    }

    @Override
    public Product save(Product product) {
        // Must load the existing entity — do NOT create a new one,
        // or Hibernate will attempt an INSERT instead of UPDATE,
        // and the @Version field won't be preserved.
        ProductEntity existing = jpaRepo.findByBusinessId(product.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot save unknown product: " + product.getId()));
        ProductEntity updated = mapper.toEntity(product, existing);
        return mapper.toDomain(jpaRepo.save(updated));
    }
}