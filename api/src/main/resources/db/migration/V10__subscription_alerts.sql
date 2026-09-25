-- M5 / CP5.3: prediction and alerts for confirmed subscriptions (DESIGN: Recurrence detection, step 5).
-- PRICE_CHANGE: a charge outside expected ± tolerance. MISSED: no charge by next expected + tolerance + 3 days.
-- An alert is a question for the user; its resolution records the answer (or why it closed by itself).

CREATE TABLE subscription_alert (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id       bigint NOT NULL REFERENCES subscription (id),
    kind                  text   NOT NULL CHECK (kind IN ('PRICE_CHANGE', 'MISSED')),
    transaction_id        bigint REFERENCES transaction (id), -- PRICE_CHANGE: the charge with the new amount
    due_date              date,                               -- MISSED: the due date nothing arrived for
    previous_amount_minor bigint,
    amount_minor          bigint,
    -- ACKNOWLEDGED: new price accepted; ENDED / CANCELLED: the user ended it; STILL_ACTIVE: skip that period;
    -- CHARGED: the charge arrived after all; SYSTEM_ENDED: two missed in a row ended it.
    resolution            text   CHECK (resolution IN ('ACKNOWLEDGED', 'ENDED', 'CANCELLED', 'STILL_ACTIVE', 'CHARGED',
                                                       'SYSTEM_ENDED')),
    created_at            timestamptz NOT NULL DEFAULT now(),
    resolved_at           timestamptz,
    CHECK ((kind = 'PRICE_CHANGE') = (transaction_id IS NOT NULL)),
    CHECK ((kind = 'MISSED') = (due_date IS NOT NULL)),
    CONSTRAINT subscription_alert_charge_uq UNIQUE (subscription_id, kind, transaction_id),
    CONSTRAINT subscription_alert_due_uq UNIQUE (subscription_id, kind, due_date)
);

-- Price checks start after the charges the user saw when confirming (or accepting a new price).
ALTER TABLE subscription ADD COLUMN confirmed_through date;
