ALTER TABLE products
    ADD COLUMN IF NOT EXISTS stock_quantity INTEGER NOT NULL DEFAULT 100;

CREATE INDEX IF NOT EXISTS idx_products_stock_quantity ON products (stock_quantity);
