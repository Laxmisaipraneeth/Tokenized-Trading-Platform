CREATE TABLE transactions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id              UUID NOT NULL REFERENCES wallets(id),
    counterparty_wallet_id UUID REFERENCES wallets(id),
    type                   VARCHAR(10) NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount                 DECIMAL(28, 8) NOT NULL,
    balance_after          DECIMAL(28, 8) NOT NULL,
    reference_id           UUID,
    reference_type         VARCHAR(50),
    created_at             TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_wallet_created ON transactions(wallet_id, created_at DESC);
CREATE INDEX idx_transactions_reference      ON transactions(reference_id);
