package com.bowt.backend.orderprocessing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class Product {

    private String id;       // business key e.g. "PROD-001"
    private String sku;
    private String name;
    private Money price;
    private int inventoryQuantity;
    private int version;

    public Product() {}

    public boolean hasStock(int requested) {
        return this.inventoryQuantity >= requested;
    }
}
