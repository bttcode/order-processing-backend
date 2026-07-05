package com.bowt.backend.orderprocessing.infrastructure.persistence.adapter;

import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.exception.ProductNotFoundException;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * findById + save is 2 round-trips per inventory update. This is architecturally correct,
 * not a bug: ProductJpaAdapter must reload the entity to obtain its `pk` and current
 *
 * @Version, because the domain Product carries neither. Passing a raw ProductEntity into
 * the application/domain layer would violate the hexagonal boundary (AR-1).
 * <p>
 * [Phase 4 track] Once InventoryService moves to application/ (per domain-layer-review P2),
 * consider ProductRepository.saveWithKnownVersion(Product, int version) to let the adapter
 * skip the reload when the version is already known from an earlier findById in the same
 * transaction. Not implemented here — premature until the calling service is refactored.
 */
@Repository
@RequiredArgsConstructor
public class ProductJpaAdapter implements ProductRepository {

    private final JpaProductRepository jpaRepository;
    private final ProductMapper mapper;

    @Override
    public Optional<Product> findById(String businessId) {
        return jpaRepository.findByBusinessId(businessId).map(mapper::toDomain);
    }

    @Override
    public List<Product> findAllById(List<String> businessIds) {
        return jpaRepository.findAllByBusinessIds(businessIds).stream()
                .map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public Product save(Product product) {
        ProductEntity entity = jpaRepository.findByBusinessId(product.getId())
                .orElseThrow(() -> new ProductNotFoundException(product.getId()));
        mapper.updateEntity(entity, product);
        ProductEntity saved = jpaRepository.save(entity); // version check fires here
        return mapper.toDomain(saved);
    }
}