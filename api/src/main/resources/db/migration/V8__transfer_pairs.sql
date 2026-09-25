-- M5 / CP5.1: internal transfer pairing (DESIGN: Internal transfer detection). A pair is an interpretation of two
-- facts, so it lives in its own table and is recomputable. PROVISIONAL = one side only, recognised by the counterparty
-- IBAN naming another own account; it becomes PAIRED when the other statement arrives.

CREATE TABLE transfer_pair (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id       bigint  NOT NULL REFERENCES household (id),
    out_transaction_id bigint  NOT NULL UNIQUE REFERENCES transaction (id),
    in_transaction_id  bigint  NOT NULL UNIQUE REFERENCES transaction (id),
    -- IBAN = a counterparty IBAN confirmed the pair; AMOUNT_DATE = amount and dates alone.
    method             text    NOT NULL CHECK (method IN ('IBAN', 'AMOUNT_DATE')),
    business_days      integer NOT NULL CHECK (business_days BETWEEN 0 AND 3),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (out_transaction_id <> in_transaction_id)
);

ALTER TABLE transaction
    ADD COLUMN transfer_pair_id    bigint REFERENCES transfer_pair (id),
    ADD COLUMN transfer_state      text CHECK (transfer_state IN ('PAIRED', 'PROVISIONAL')),
    -- The other own account: the pair's other side, or the one the counterparty IBAN names.
    ADD COLUMN transfer_account_id bigint REFERENCES account (id),
    ADD CONSTRAINT transaction_transfer_ck CHECK (
        (transfer_pair_id IS NOT NULL) = (transfer_state IS NOT DISTINCT FROM 'PAIRED')
        AND (transfer_state IS NULL) = (transfer_account_id IS NULL));

CREATE INDEX transaction_transfer_pair_idx ON transaction (transfer_pair_id);
