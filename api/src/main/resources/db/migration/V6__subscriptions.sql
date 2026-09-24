-- M4 / CP4.2: subscriptions and their lifecycle (DESIGN: Data model, Subscription candidate lifecycle).
-- Candidates and confirmed subscriptions share one table, told apart by state. The detector only ever creates,
-- updates or drops PROPOSED rows; every other state is a user decision (or SYSTEM-ended, see ended_by).

CREATE TABLE subscription (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id          bigint  NOT NULL REFERENCES household (id),
    account_id            bigint  NOT NULL REFERENCES account (id),
    merchant_id           bigint  NOT NULL REFERENCES merchant (id),
    name                  text    NOT NULL CHECK (name <> ''),
    currency              char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    cadence               text    NOT NULL CHECK (cadence IN ('DAILY', 'WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    interval_n            integer NOT NULL DEFAULT 1 CHECK (interval_n >= 1),
    anchor_day            smallint CHECK (anchor_day BETWEEN 1 AND 31),
    anchor_month          smallint CHECK (anchor_month BETWEEN 1 AND 12),
    amount_kind           text    NOT NULL CHECK (amount_kind IN ('FIXED', 'VARIABLE')),
    expected_amount_minor bigint  NOT NULL CHECK (expected_amount_minor > 0), -- money out, positive
    -- A charge matches when within expected ± tolerance (2 × MAD; 0 for fixed amounts). Kept in minor units rather
    -- than DESIGN's tolerance_pct so that no amount is ever computed in floating point.
    tolerance_minor       bigint  NOT NULL CHECK (tolerance_minor >= 0),
    band_min_minor        bigint  NOT NULL,
    band_max_minor        bigint  NOT NULL CHECK (band_max_minor >= band_min_minor),
    confidence            numeric(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    first_seen            date    NOT NULL,
    last_seen             date    NOT NULL,
    next_expected_date    date,
    state                 text    NOT NULL CHECK (state IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'ENDED')),
    -- USER = the user ended it (never resumed by automation); SYSTEM = two missed charges (CP5.3), resumes on a charge.
    ended_by              text    CHECK (ended_by IN ('USER', 'SYSTEM')),
    decided_at            timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscription_ended_by_ck CHECK ((state = 'ENDED') = (ended_by IS NOT NULL))
);

CREATE INDEX subscription_account_merchant_idx ON subscription (account_id, merchant_id);

ALTER TABLE transaction ADD COLUMN subscription_id bigint REFERENCES subscription (id);
CREATE INDEX transaction_subscription_idx ON transaction (subscription_id);

-- "Not a subscription": the detector skips this (merchant, cadence, amount) unless the pattern changes materially
-- (another cadence, or an expected amount more than 50% away from amount_minor).
CREATE TABLE subscription_rejection (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id    bigint  NOT NULL REFERENCES household (id),
    merchant_id     bigint  NOT NULL REFERENCES merchant (id),
    currency        char(3) NOT NULL,
    cadence         text    NOT NULL,
    amount_minor    bigint  NOT NULL CHECK (amount_minor > 0),
    subscription_id bigint  REFERENCES subscription (id),
    rejected_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX subscription_rejection_merchant_idx ON subscription_rejection (merchant_id);
