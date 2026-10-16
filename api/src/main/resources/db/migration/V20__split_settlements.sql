-- CP6.22: settling shared expenses. A settlement says how much of what a person owes has been paid back: linked to
-- the money-in transaction that brought it (one repayment can cover several splits), or paid outside the bank
-- (cash, another account not uploaded) when transaction_id is NULL.

CREATE TABLE split_settlement (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id    bigint NOT NULL REFERENCES household (id),
    split_person_id bigint NOT NULL REFERENCES expense_split_person (id),
    transaction_id  bigint REFERENCES transaction (id),
    amount_minor    bigint NOT NULL CHECK (amount_minor > 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX split_settlement_person_idx ON split_settlement (split_person_id);
CREATE INDEX split_settlement_transaction_idx ON split_settlement (transaction_id);

-- The part of a money-in transaction that pays back shared expenses: it is neither income nor a refund, so figures
-- leave it out (txn.Scope.MINE = amount_minor + others_share_minor - repaid_minor). 0 when it repays nothing.
ALTER TABLE transaction
    ADD COLUMN repaid_minor bigint NOT NULL DEFAULT 0 CHECK (repaid_minor >= 0),
    ADD CONSTRAINT transaction_repaid_ck CHECK (repaid_minor <= greatest(amount_minor, 0));
