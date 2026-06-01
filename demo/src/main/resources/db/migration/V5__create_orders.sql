CREATE TABLE orders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    asset_id         INTEGER NOT NULL REFERENCES assets(id),
    side             VARCHAR(4)  NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type             VARCHAR(6)  NOT NULL DEFAULT 'LIMIT' CHECK (type IN ('LIMIT', 'MARKET')),
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                         CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED')),
    price            DECIMAL(28, 8) NOT NULL,
    quantity         DECIMAL(28, 8) NOT NULL,
    filled_quantity  DECIMAL(28, 8) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT orders_price_positive     CHECK (price > 0),
    CONSTRAINT orders_quantity_positive  CHECK (quantity > 0),
    CONSTRAINT orders_filled_lte_qty     CHECK (filled_quantity <= quantity)
);

CREATE INDEX idx_orders_user_id    ON orders(user_id);
CREATE INDEX idx_orders_asset_side ON orders(asset_id, side, status);
CREATE INDEX idx_orders_created_at ON orders(created_at DESC);
