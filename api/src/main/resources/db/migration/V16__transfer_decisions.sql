-- CP6.12: "Which transfer is this?" (DESIGN: Internal transfer detection, "ties go to review"). A tie is a transaction
-- with more than one equally good partner in another own account; the automation leaves it unpaired and asks. The
-- user's answers are kept for good: SAME makes that pair (source USER), DIFFERENT means these two never pair.

CREATE TABLE transfer_decision (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id       bigint NOT NULL REFERENCES household (id),
    out_transaction_id bigint NOT NULL REFERENCES transaction (id),
    in_transaction_id  bigint NOT NULL REFERENCES transaction (id),
    decision           text   NOT NULL CHECK (decision IN ('SAME', 'DIFFERENT')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT transfer_decision_pair_uq UNIQUE (out_transaction_id, in_transaction_id),
    CHECK (out_transaction_id <> in_transaction_id)
);

-- AUTO: found by amount, dates and IBANs; USER: the user picked it on a tie card.
ALTER TABLE transfer_pair
    ADD COLUMN source text NOT NULL DEFAULT 'AUTO' CHECK (source IN ('AUTO', 'USER'));
