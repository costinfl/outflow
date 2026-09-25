-- Recurring income: a detected stream is money sent (OUT: subscriptions and bills) or money received (IN: a salary).
-- Only OUT streams are commitments; IN streams are listed as income.
ALTER TABLE subscription ADD COLUMN direction text NOT NULL DEFAULT 'OUT' CHECK (direction IN ('IN', 'OUT'));
ALTER TABLE subscription_rejection ADD COLUMN direction text NOT NULL DEFAULT 'OUT' CHECK (direction IN ('IN', 'OUT'));
