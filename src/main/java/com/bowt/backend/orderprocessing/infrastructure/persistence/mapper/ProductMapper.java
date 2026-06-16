package com.bowt.backend.orderprocessing.infrastructure.persistence.mapper;

import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public Product toDomain(ProductEntity entity) {
        Product p = new Product();
        p.setId(entity.getId());
        p.setSku(entity.getSku());
        p.setName(entity.getName());
        p.setPrice(Money.of(entity.getPrice()));
        p.setInventoryQuantity(entity.getInventoryQuantity());
        p.setVersion(entity.getVersion());
        return p;
    }

    public ProductEntity toEntity(Product product, ProductEntity existing) {
        // Update existing entity (never create a new one for inventory updates
        // — must preserve the BIGINT pk and @Version field)
        existing.setInventoryQuantity(product.getInventoryQuantity());
        existing.setName(product.getName());
        existing.setPrice(product.getPrice().getAmount());
        // id, sku, pk — never change after creation
        return existing;
    }
}