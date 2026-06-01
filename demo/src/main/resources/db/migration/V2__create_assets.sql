CREATE TABLE assets (
    id            SERIAL PRIMARY KEY,
    symbol        VARCHAR(10) UNIQUE NOT NULL,
    name          VARCHAR(255) NOT NULL,
    decimal_places INT NOT NULL DEFAULT 8,
    is_tradeable  BOOLEAN NOT NULL DEFAULT TRUE,
    is_cash       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

-- CASH is the base currency used to buy/sell tokens
INSERT INTO assets (symbol, name, decimal_places, is_tradeable, is_cash)
VALUES ('CASH', 'Cash (USD)', 2, FALSE, TRUE);

-- Tradeable tokenized assets
INSERT INTO assets (symbol, name, decimal_places, is_tradeable, is_cash)
VALUES
    ('GOLD', 'Gold Token',          8, TRUE, FALSE),
    ('SLVR', 'Silver Token',        8, TRUE, FALSE),
    ('REIT', 'Real Estate Token',   8, TRUE, FALSE);
