package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.model.Product;

import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(String businessId);

    Product save(Product product);
}
