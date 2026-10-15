-- CP6.21: shared expenses ("this should be split"). The user paid for something shared and other people owe part of
-- it: spending counts only the user's own share. One split per transaction, one mode per split (EQUAL: the user and
-- each person alike; PERCENT / FIXED: each person's part, the rest is the user's; SHARES: weights, the user's
-- included). Each person's amount is computed when the split is saved and stored in minor units.

CREATE TABLE expense_split (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id   bigint  NOT NULL REFERENCES household (id),
    transaction_id bigint  NOT NULL UNIQUE REFERENCES transaction (id),
    mode           text    NOT NULL CHECK (mode IN ('EQUAL', 'PERCENT', 'FIXED', 'SHARES')),
    my_shares      integer CHECK (my_shares >= 0),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CHECK ((mode = 'SHARES') = (my_shares IS NOT NULL))
);

CREATE TABLE expense_split_person (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    split_id     bigint  NOT NULL REFERENCES expense_split (id) ON DELETE CASCADE,
    position     integer NOT NULL,
    name         text    NOT NULL CHECK (btrim(name) <> ''),
    percent      numeric(5, 2) CHECK (percent > 0 AND percent <= 100),
    fixed_minor  bigint  CHECK (fixed_minor > 0),
    shares       integer CHECK (shares > 0),
    amount_minor bigint  NOT NULL CHECK (amount_minor >= 0),
    CONSTRAINT expense_split_person_name_uq UNIQUE (split_id, name),
    CONSTRAINT expense_split_person_position_uq UNIQUE (split_id, position)
);

-- What other people owe of a money-out transaction, kept with it so every figure can count only the user's share
-- (txn.Scope.MINE = amount_minor + others_share_minor). 0 when not split.
ALTER TABLE transaction
    ADD COLUMN others_share_minor bigint NOT NULL DEFAULT 0 CHECK (others_share_minor >= 0),
    ADD CONSTRAINT transaction_others_share_ck CHECK (others_share_minor <= greatest(-amount_minor, 0));
