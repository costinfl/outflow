-- Insurance (spec question 27, decided by the user): premiums are spending, and recurring ones are bills. Seeded with
-- a fixed id next to the V4 tree (user-added categories start at 1001).
INSERT INTO category (id, code, name, kind, sort_order) VALUES (19, 'INSURANCE', 'Insurance', 'SPEND', 145);

INSERT INTO category_rule (household_id, source, match_type, pattern, category_id)
SELECT 1, 'SEED', 'KEYWORD', k.pattern, 19
FROM (VALUES
    ('ASIGURARI'), ('ASIGURARE'), ('ASIGURAREA'), ('INSURANCE'), ('RCA'), ('CASCO'), ('ALLIANZ'), ('GROUPAMA'),
    ('OMNIASIG'), ('GENERALI'), ('UNIQA'), ('EUROINS'), ('ASIROM'), ('SIGNAL IDUNA'), ('METROPOLITAN LIFE'),
    ('NN ASIGURARI'), ('GRAWE'), ('HELVETIA'), ('POOL PAID')
) AS k(pattern)
ON CONFLICT ON CONSTRAINT category_rule_uq DO NOTHING;
