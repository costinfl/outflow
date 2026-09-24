-- M2 / CP2.1: merchants (DESIGN: Merchant normalization and classification).
-- A merchant is an interpretation of raw descriptions: transaction.merchant_id is recomputable at any time.

CREATE TABLE merchant (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key          text NOT NULL CHECK (key <> '' AND key = upper(key)),
    display_name text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT merchant_key_uq UNIQUE (key)
);

-- Step 5 of normalization. SEED rows ship with the app; USER rows come from the raw → key debug view (CP2.3).
CREATE TABLE merchant_alias (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    match_type   text NOT NULL CHECK (match_type IN ('EXACT', 'PREFIX')),
    pattern      text NOT NULL CHECK (pattern <> '' AND pattern = upper(pattern)),
    merchant_key text NOT NULL CHECK (merchant_key <> '' AND merchant_key = upper(merchant_key)),
    source       text NOT NULL CHECK (source IN ('SEED', 'USER')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT merchant_alias_uq UNIQUE (match_type, pattern)
);

ALTER TABLE transaction ADD COLUMN merchant_id bigint REFERENCES merchant (id);
CREATE INDEX transaction_merchant_idx ON transaction (merchant_id);

-- Chains whose raw strings carry a location or branch after the name: one prefix alias keeps them one merchant.
INSERT INTO merchant_alias (match_type, pattern, merchant_key, source) VALUES
    ('PREFIX', 'KAUFLAND', 'KAUFLAND', 'SEED'),
    ('PREFIX', 'LIDL', 'LIDL', 'SEED'),
    ('PREFIX', 'MEGA IMAGE', 'MEGA IMAGE', 'SEED'),
    ('PREFIX', 'CARREFOUR', 'CARREFOUR', 'SEED'),
    ('PREFIX', 'AUCHAN', 'AUCHAN', 'SEED'),
    ('PREFIX', 'PROFI', 'PROFI', 'SEED'),
    ('PREFIX', 'PENNY', 'PENNY', 'SEED'),
    ('PREFIX', 'LA DOI PASI', 'LA DOI PASI', 'SEED'),
    ('PREFIX', 'DM DROGERIE', 'DM', 'SEED'),
    ('PREFIX', 'STARBUCKS', 'STARBUCKS', 'SEED'),
    ('PREFIX', 'MCDONALDS', 'MCDONALDS', 'SEED'),
    ('PREFIX', 'KFC', 'KFC', 'SEED'),
    ('PREFIX', 'SPOTIFY', 'SPOTIFY', 'SEED'),
    ('PREFIX', 'NETFLIX', 'NETFLIX', 'SEED'),
    ('PREFIX', 'HBO MAX', 'HBO MAX', 'SEED'),
    ('PREFIX', 'DISNEY PLUS', 'DISNEY PLUS', 'SEED'),
    ('PREFIX', 'APPLE COM BILL', 'APPLE', 'SEED'),
    ('PREFIX', 'GOOGLE', 'GOOGLE', 'SEED'),
    ('PREFIX', 'AMZN MKTP', 'AMAZON', 'SEED'),
    ('PREFIX', 'AMZN', 'AMAZON', 'SEED'),
    ('PREFIX', 'AMAZON', 'AMAZON', 'SEED'),
    ('PREFIX', 'EMAG', 'EMAG', 'SEED'),
    ('PREFIX', 'ALTEX', 'ALTEX', 'SEED'),
    ('PREFIX', 'DEDEMAN', 'DEDEMAN', 'SEED'),
    ('PREFIX', 'IKEA', 'IKEA', 'SEED'),
    ('PREFIX', 'GLOVO', 'GLOVO', 'SEED'),
    ('PREFIX', 'BOLT', 'BOLT', 'SEED'),
    ('PREFIX', 'UBER', 'UBER', 'SEED'),
    ('PREFIX', 'OMV', 'OMV', 'SEED'),
    ('PREFIX', 'PETROM', 'PETROM', 'SEED'),
    ('PREFIX', 'MOL', 'MOL', 'SEED'),
    ('PREFIX', 'ROMPETROL', 'ROMPETROL', 'SEED'),
    ('PREFIX', 'LUKOIL', 'LUKOIL', 'SEED'),
    ('PREFIX', 'CATENA', 'CATENA', 'SEED'),
    ('PREFIX', 'DR MAX', 'DR MAX', 'SEED'),
    ('PREFIX', 'HELP NET', 'HELP NET', 'SEED'),
    ('PREFIX', 'ENEL', 'ENEL', 'SEED'),
    ('PREFIX', 'ENGIE', 'ENGIE', 'SEED'),
    ('PREFIX', 'E.ON', 'EON', 'SEED'),
    ('PREFIX', 'EON', 'EON', 'SEED'),
    ('PREFIX', 'DIGI', 'DIGI', 'SEED'),
    ('PREFIX', 'RCS RDS', 'DIGI', 'SEED'),
    ('PREFIX', 'ORANGE', 'ORANGE', 'SEED'),
    ('PREFIX', 'VODAFONE', 'VODAFONE', 'SEED'),
    ('PREFIX', 'TELEKOM', 'TELEKOM', 'SEED');
