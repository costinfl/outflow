-- CP6.24: the ING savings account ("cont de economii") books its monthly interest as "Actualizare dobanda" (the
-- deposit's interest was already seeded in V5 as "Dobanda aferenta"). Interest is income.

INSERT INTO category_rule (household_id, source, match_type, pattern, category_id)
SELECT 1, 'SEED', 'KEYWORD', 'ACTUALIZARE DOBANDA', c.id FROM category c WHERE c.code = 'INCOME'
ON CONFLICT ON CONSTRAINT category_rule_uq DO NOTHING;
