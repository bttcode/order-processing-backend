package com.bowt.backend.orderprocessing.infrastructure.persistence;

import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DataSeeder {

    private final JpaProductRepository repo;

    @PostConstruct
    @Transactional
    public void seed() {
        if (repo.count() > 0) return;
        repo.save(buildProduct("PROD-001", "WIDGET-001", "Wireless Mouse", 49.99, 100));
        repo.save(buildProduct("PROD-002", "WIDGET-002", "Mechanical Keyboard", 129.99, 50));
        repo.save(buildProduct("PROD-003", "WIDGET-003", "USB-C Hub", 39.99, 200));
    }

    private ProductEntity buildProduct(String id, String sku, String name,
                                       double price, int qty) {
        ProductEntity p = new ProductEntity();
        p.setId(id);
        p.setSku(sku);
        p.setName(name);
        p.setPrice(java.math.BigDecimal.valueOf(price));
        p.setInventoryQuantity(qty);
        return p;
    }
}
