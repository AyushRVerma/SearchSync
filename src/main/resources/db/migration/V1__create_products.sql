SET ROLE inventory_owner;

CREATE TABLE products (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000),
    category VARCHAR(100) NOT NULL,
    price NUMERIC(19, 2) NOT NULL CHECK (price >= 0),
    stock_quantity INTEGER NOT NULL CHECK (stock_quantity >= 0),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_products_sku UNIQUE (sku)
);

CREATE INDEX idx_products_category ON products (category);
CREATE INDEX idx_products_active ON products (active);

RESET ROLE;

