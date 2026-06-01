CREATE TABLE wallets (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id),
    asset_id          INT  NOT NULL REFERENCES assets(id),
    available_balance DECIMAL(28, 8) NOT NULL DEFAULT 0,
    locked_balance    DECIMAL(28, 8) NOT NULL DEFAULT 0,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_user_asset    UNIQUE (user_id, asset_id),
    CONSTRAINT non_negative_available CHECK (available_balance >= 0),
    CONSTRAINT non_negative_locked    CHECK (locked_balance    >= 0)
);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);
