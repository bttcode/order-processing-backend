package com.bowt.backend.orderprocessing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@AllArgsConstructor
public class Product {

    private final String id; // business key e.g. "PROD-001"
    private final String sku;
    private final String name;
    private final Money price;
    private int inventoryQuantity;

    public boolean isAvailable(int requested) {
        return inventoryQuantity >= requested;
    }

    public boolean deductStock(int requested) {
        if (requested <= 0 || !isAvailable(requested)) {
            return false;
        }
        inventoryQuantity -= requested;
        return true;
    }

    public boolean restoreStock(int requested) {
        if (requested <= 0) {
            return false;
        }
        inventoryQuantity += requested;
        return true;
    }
}