package com.bowt.backend.orderprocessing.infrastructure.persistence;

import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final JpaProductRepository repository;

    @Override
    @Transactional
    public void run(String... args) {
        if (repository.count() > 0) {
            return; // idempotent — don't reseed on every restart
        }
        repository.save(buildProduct("PROD-001", "WIDGET-001", "Wireless Mouse", "49.99", 100));
        repository.save(buildProduct("PROD-002", "WIDGET-002", "Mechanical Keyboard", "129.99", 50));
        repository.save(buildProduct("PROD-003", "WIDGET-003", "USB-C Hub", "39.99", 200));
        repository.save(buildProduct("PROD-004", "WIDGET-004", "27in Monitor", "249.99", 30));
        repository.save(buildProduct("PROD-005", "WIDGET-005", "Noise-Cancelling Headphones", "199.99", 75));
    }

    private ProductEntity buildProduct(String id, String sku, String name, String price, int qty) {
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        entity.setSku(sku);
        entity.setName(name);
        entity.setPrice(new BigDecimal(price));
        entity.setInventoryQuantity(qty);
        return entity;
    }
}