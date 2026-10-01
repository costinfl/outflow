-- CP6.16: split (DESIGN: Subscription candidate lifecycle, "two plans from one merchant"). The user's amount cut for
-- one merchant's charges on one account: amounts below it and amounts at or above it are separate streams for the
-- detector, even when they are within 25% of each other. Kept for good, like every user decision.

CREATE TABLE subscription_split (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id bigint  NOT NULL REFERENCES household (id),
    account_id   bigint  NOT NULL REFERENCES account (id),
    merchant_id  bigint  NOT NULL REFERENCES merchant (id),
    currency     char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    direction    text    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    cut_minor    bigint  NOT NULL CHECK (cut_minor > 0), -- positive minor units, like the charges' amounts
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscription_split_uq UNIQUE (account_id, merchant_id, currency, direction, cut_minor)
);
