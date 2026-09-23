-- Baseline. Schema starts in M1 (CP1.1) with its own migration.
-- Existing migrations are never edited; every change is a new V<n>__*.sql file.
COMMENT ON SCHEMA public IS 'Outflow';
