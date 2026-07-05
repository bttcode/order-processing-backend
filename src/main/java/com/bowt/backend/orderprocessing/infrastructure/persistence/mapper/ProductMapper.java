package com.bowt.backend.orderprocessing.infrastructure.persistence.mapper;

import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public Product toDomain(ProductEntity entity) {
        return new Product(entity.getId(), entity.getSku(), entity.getName(),
                Money.of(entity.getPrice()), entity.getInventoryQuantity());
    }

    public ProductEntity toNewEntity(Product product) {
        ProductEntity entity = new ProductEntity();
        entity.setId(product.getId());
        entity.setSku(product.getSku());
        entity.setName(product.getName());
        entity.setPrice(product.getPrice().getAmount());
        entity.setInventoryQuantity(product.getInventoryQuantity());
        return entity;
    }

    /**
     * Updates fields that legitimately change post-creation (inventory, price, name).
     * Deliberately does NOT touch id, sku, or pk — those are immutable business/surrogate
     * keys. Preserves the existing entity's @Version so Hibernate's optimistic-lock
     * UPDATE ... WHERE version = ? still fires against the correct value (ADR-003).
     */
    public void updateEntity(ProductEntity entity, Product product) {
        entity.setName(product.getName());
        entity.setPrice(product.getPrice().getAmount());
        entity.setInventoryQuantity(product.getInventoryQuantity());
    }
}