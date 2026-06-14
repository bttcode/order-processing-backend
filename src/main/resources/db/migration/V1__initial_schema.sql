-- V1__initial_schema.sql

CREATE TABLE orders
(
    pk                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id                     UUID           NOT NULL,
    customer_id            VARCHAR(50)    NOT NULL,
    status                 VARCHAR(30)    NOT NULL,
    total_amount           DECIMAL(12, 2) NOT NULL,
    currency               VARCHAR(3)     NOT NULL DEFAULT 'USD',
    payment_method         VARCHAR(20),
    payment_transaction_id VARCHAR(100),
    idempotency_key        UUID UNIQUE,
    created_at             TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT NOW(),
    confirmed_at           TIMESTAMP,
    version                INTEGER        NOT NULL DEFAULT 0,

    CONSTRAINT uq_orders_id UNIQUE (id),
    CONSTRAINT chk_order_status CHECK (status IN (
                                                  'PENDING_VALIDATION', 'PENDING_PAYMENT', 'PAYMENT_AUTHORIZED',
                                                  'CONFIRMED', 'CANCELLED', 'INSUFFICIENT_INVENTORY', 'PAYMENT_FAILED'
        ))
);

CREATE TABLE products
(
    pk                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id                 VARCHAR(50)    NOT NULL,
    sku                VARCHAR(100)   NOT NULL,
    name               VARCHAR(200)   NOT NULL,
    description        TEXT,
    price              DECIMAL(12, 2) NOT NULL,
    inventory_quantity INTEGER        NOT NULL DEFAULT 0,
    version            INTEGER        NOT NULL DEFAULT 0,
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_products_id UNIQUE (id),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT chk_price CHECK (price >= 0),
    CONSTRAINT chk_inventory CHECK (inventory_quantity >= 0)
);

CREATE TABLE order_items
(
    id           BIGSERIAL PRIMARY KEY,
    order_pk     BIGINT         NOT NULL REFERENCES orders (pk) ON DELETE CASCADE,
    product_pk   BIGINT         NOT NULL REFERENCES products (pk),
    product_id   VARCHAR(50)    NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    quantity     INTEGER        NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    total_price  DECIMAL(12, 2) NOT NULL,

    CONSTRAINT chk_quantity CHECK (quantity > 0)
);

CREATE TABLE idempotency_keys
(
    key             UUID PRIMARY KEY,
    response_body   TEXT      NOT NULL,
    response_status INTEGER   NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL
);

-- orders indexes
CREATE UNIQUE INDEX idx_orders_public_id ON orders (id);
CREATE INDEX idx_order_customer_date ON orders (customer_id, created_at DESC);
CREATE INDEX idx_order_status ON orders (status) WHERE status NOT IN ('CONFIRMED','CANCELLED');
CREATE INDEX idx_order_idempotency ON orders (idempotency_key);
CREATE INDEX idx_order_composite ON orders (customer_id, status, created_at DESC);

-- products indexes
CREATE UNIQUE INDEX idx_products_business_id ON products (id);
CREATE UNIQUE INDEX idx_products_sku ON products (sku);

-- order_items indexes
CREATE INDEX idx_order_item_order_pk ON order_items (order_pk);
CREATE INDEX idx_order_item_product_pk ON order_items (product_pk);
CREATE INDEX idx_order_item_product_id ON order_items (product_id);

-- idempotency cleanup
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);