-- DESIGN: Recurring payments, row action "remind me before next charge". Days before the next expected charge that the
-- review inbox starts asking about it (NULL = no reminder), and the due date whose reminder the user already saw.
ALTER TABLE subscription ADD COLUMN remind_days_before smallint CHECK (remind_days_before BETWEEN 1 AND 14);
ALTER TABLE subscription ADD COLUMN reminded_through date;
