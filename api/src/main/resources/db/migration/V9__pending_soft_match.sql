-- M5 / CP5.2: pending vs posted (DESIGN: Transaction identity, "Pending vs posted"). A pending card payment can
-- reappear posted with another date or final amount, so its identity key differs. The pending row is superseded by the
-- posted one, never deleted; superseded rows count nowhere. AUTO links are recomputed on every run, USER ones are kept.

ALTER TABLE transaction
    ADD COLUMN superseded_by     bigint REFERENCES transaction (id),
    ADD COLUMN superseded_source text CHECK (superseded_source IN ('AUTO', 'USER')),
    ADD CONSTRAINT transaction_superseded_ck CHECK (
        (superseded_by IS NULL) = (superseded_source IS NULL)
        AND (superseded_by IS NULL OR (status = 'PENDING' AND superseded_by <> id)));

CREATE INDEX transaction_superseded_by_idx ON transaction (superseded_by);
CREATE INDEX transaction_pending_idx ON transaction (account_id) WHERE status = 'PENDING';

-- Several possible matches: the user decides (review card "Possible duplicate"). DESIGN's txn_a / txn_b.
CREATE TABLE soft_match_review (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id           bigint NOT NULL REFERENCES household (id),
    pending_transaction_id bigint NOT NULL REFERENCES transaction (id),
    posted_transaction_id  bigint NOT NULL REFERENCES transaction (id),
    reason                 text   NOT NULL,
    -- SAME: the user linked them; DIFFERENT: never match these two again.
    resolution             text   CHECK (resolution IN ('SAME', 'DIFFERENT')),
    created_at             timestamptz NOT NULL DEFAULT now(),
    resolved_at            timestamptz,
    CONSTRAINT soft_match_review_pair_uq UNIQUE (pending_transaction_id, posted_transaction_id)
);
