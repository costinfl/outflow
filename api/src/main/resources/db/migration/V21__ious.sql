-- CP6.23: "they paid, I owe". Someone paid for something shared and the user owes them a share. No bank money moved
-- for the user then, so an IOU counts nowhere; the money out that pays it back later is the user's spending, in the
-- IOU's category (the user links them). Paid outside the bank (cash) when transaction_id is NULL.

CREATE TABLE iou (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id bigint  NOT NULL REFERENCES household (id),
    person       text    NOT NULL CHECK (btrim(person) <> '' AND length(person) <= 60),
    description  text    NOT NULL CHECK (btrim(description) <> '' AND length(description) <= 120),
    incurred_on  date    NOT NULL,
    currency     char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount_minor bigint  NOT NULL CHECK (amount_minor > 0),
    category_id  bigint  REFERENCES category (id),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE iou_payment (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id   bigint NOT NULL REFERENCES household (id),
    iou_id         bigint NOT NULL REFERENCES iou (id),
    transaction_id bigint REFERENCES transaction (id),
    amount_minor   bigint NOT NULL CHECK (amount_minor > 0),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX iou_payment_iou_idx ON iou_payment (iou_id);
CREATE INDEX iou_payment_transaction_idx ON iou_payment (transaction_id);
