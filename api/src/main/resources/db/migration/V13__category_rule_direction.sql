-- A user rule can apply to money sent (OUT) or money received (IN) only: a person you pay rent to may also pay you
-- back, and that money is not rent. NULL applies to both directions (every seed rule, and "apply to all").
ALTER TABLE category_rule ADD COLUMN direction text CHECK (direction IN ('IN', 'OUT'));

ALTER TABLE category_rule DROP CONSTRAINT category_rule_uq;
ALTER TABLE category_rule ADD CONSTRAINT category_rule_uq
    UNIQUE NULLS NOT DISTINCT (household_id, source, match_type, pattern, direction);
