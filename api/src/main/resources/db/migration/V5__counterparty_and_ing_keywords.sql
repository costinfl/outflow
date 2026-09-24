-- ING Romania support: bank-specific parsers can name the counterparty explicitly (a fact from the raw row, used for
-- merchant detection instead of the full description), and seed keywords for ING's own transaction types.

ALTER TABLE transaction ADD COLUMN counterparty_raw text;

INSERT INTO category_rule (household_id, source, match_type, pattern, category_id)
SELECT 1, 'SEED', 'KEYWORD', k.pattern, c.id
FROM (VALUES
    ('TRANSFER', 'ROUND UP'),            -- ING Round Up: spare change moved to the savings account
    ('TRANSFER', 'DEPOZIT'),             -- constituire / lichidare depozit: own deposit account
    ('TRANSFER', 'SCHIMB VALUTAR'),      -- currency exchange between own accounts
    ('INCOME', 'DOBANDA AFERENTA'),      -- interest paid on a deposit
    ('FEES', 'TAXE SI COMISIOANE')
) AS k(code, pattern)
JOIN category c ON c.code = k.code
ON CONFLICT ON CONSTRAINT category_rule_uq DO NOTHING;
