-- CP6.19: transfers between own accounts in different currencies (DESIGN: Internal transfer detection, "within FX
-- tolerance for cross-currency"; spec question 21). No rate source leaves the machine: the user sets an approximate
-- rate per currency pair and how far a transfer may be from it. One row per pair, in alphabetical order: EUR/RON is
-- "1 EUR = rate RON".

CREATE TABLE fx_rate (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id      bigint        NOT NULL REFERENCES household (id),
    base_currency     char(3)       NOT NULL CHECK (base_currency ~ '^[A-Z]{3}$'),
    quote_currency    char(3)       NOT NULL CHECK (quote_currency ~ '^[A-Z]{3}$'),
    rate              numeric(20, 10) NOT NULL CHECK (rate > 0),
    tolerance_percent numeric(4, 2) NOT NULL DEFAULT 3 CHECK (tolerance_percent BETWEEN 0 AND 10),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fx_rate_pair_uq UNIQUE (household_id, base_currency, quote_currency),
    CHECK (base_currency < quote_currency)
);

-- FX_RATE: two currencies, the amounts within the user's rate and tolerance (IBAN still wins when an IBAN confirms it).
ALTER TABLE transfer_pair DROP CONSTRAINT transfer_pair_method_check;
ALTER TABLE transfer_pair
    ADD CONSTRAINT transfer_pair_method_check CHECK (method IN ('IBAN', 'AMOUNT_DATE', 'FX_RATE'));
