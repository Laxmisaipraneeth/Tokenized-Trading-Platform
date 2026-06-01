CREATE TABLE trades (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buy_order_id    UUID NOT NULL REFERENCES orders(id),
    sell_order_id   UUID NOT NULL REFERENCES orders(id),
    buyer_id        UUID NOT NULL REFERENCES users(id),
    seller_id       UUID NOT NULL REFERENCES users(id),
    asset_id        INTEGER NOT NULL REFERENCES assets(id),
    price           DECIMAL(28, 8) NOT NULL,
    quantity        DECIMAL(28, 8) NOT NULL,
    total_amount    DECIMAL(28, 8) NOT NULL,
    executed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT trades_price_positive    CHECK (price > 0),
    CONSTRAINT trades_quantity_positive CHECK (quantity > 0),
    CONSTRAINT trades_total_consistent  CHECK (total_amount = price * quantity)
);

CREATE INDEX idx_trades_buyer        ON trades(buyer_id, executed_at DESC);
CREATE INDEX idx_trades_seller       ON trades(seller_id, executed_at DESC);
CREATE INDEX idx_trades_asset        ON trades(asset_id, executed_at DESC);
CREATE INDEX idx_trades_buy_order    ON trades(buy_order_id);
CREATE INDEX idx_trades_sell_order   ON trades(sell_order_id);
