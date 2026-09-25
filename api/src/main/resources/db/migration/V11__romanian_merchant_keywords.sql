-- More seed keywords for Romanian merchants, from what the real (anonymized) ING export left uncategorized.
-- Generic chains and kinds of business only: nothing specific to one person. Keywords match whole words of the
-- merchant key (after normalization: processor prefixes such as PAYU* / MOBILPAY* are already stripped).

INSERT INTO category_rule (household_id, source, match_type, pattern, category_id)
SELECT 1, 'SEED', 'KEYWORD', k.pattern, c.id
FROM (VALUES
    ('GROCERIES', 'MEGAIMAGE'),
    ('RESTAURANTS', 'MCD'), ('RESTAURANTS', 'VENDING'), ('RESTAURANTS', 'COFETARIA'), ('RESTAURANTS', 'GELATO'),
    ('RESTAURANTS', 'TACO BELL'), ('RESTAURANTS', 'TERASA'), ('RESTAURANTS', 'BERE'),
    ('RESTAURANTS', 'BERARIE'), ('RESTAURANTS', 'BERARILOR'), ('RESTAURANTS', 'COFFE'), ('RESTAURANTS', 'OFRESH'),
    ('RESTAURANTS', 'PIZZERIA'), ('RESTAURANTS', 'SHAORMA'),
    ('HEALTH', 'MEDICAL'), ('HEALTH', 'CLINICA'), ('HEALTH', 'POLICLINICA'), ('HEALTH', 'HOSPITAL'),
    ('HEALTH', 'SPITAL'), ('HEALTH', 'LABORATOR'), ('HEALTH', 'FARM'), ('HEALTH', 'PHARMA'), ('HEALTH', 'PHARMALIFE'),
    ('HEALTH', 'BENU'), ('HEALTH', 'EUROCLINIC'), ('HEALTH', 'ORTOPEDIE'),
    ('HEALTH', 'AMBULATORIU'), ('HEALTH', 'DENTAL'), ('HEALTH', 'STOMATOLOGIE'),
    ('SHOPPING', 'SMYK'), ('SHOPPING', 'PRIMARK'), ('SHOPPING', 'LEROY MERLIN'), ('SHOPPING', 'BRICO'),
    ('SHOPPING', 'HORNBACH'), ('SHOPPING', 'FLANCO'), ('SHOPPING', 'DRM'), ('SHOPPING', 'BEBETEI'),
    ('SHOPPING', 'EASYBOX'), ('SHOPPING', 'ROMSTAL'), ('SHOPPING', 'FOCADO'), ('SHOPPING', 'MAGNOLIA'),
    ('SHOPPING', 'RESERVED'), ('SHOPPING', 'FLORARIE'),
    ('ENTERTAINMENT', 'CINEMACITY'), ('ENTERTAINMENT', 'CINE'), ('ENTERTAINMENT', 'LOTO'),
    ('ENTERTAINMENT', 'IA BILET'),
    ('TRANSPORT', 'AMPARCAT'), ('TRANSPORT', 'CNADNR'), ('TRANSPORT', 'ROVINIETA'),
    ('SUBSCRIPTIONS', 'JETBRAINS'), ('SUBSCRIPTIONS', 'LEGE5'),
    ('FEES', 'IMPOZI'), ('FEES', 'IMPOZITE'), ('FEES', 'ANCPI'), ('FEES', 'ANAF'), ('FEES', 'GHISEUL'),
    ('EDUCATION', 'SCOALA'), ('EDUCATION', 'GRADINITA'), ('EDUCATION', 'UNIVERSITATEA'), ('EDUCATION', 'UNSTPB'),
    ('EDUCATION', 'LIBRIS'), ('EDUCATION', 'ELEFANT'),
    ('SHOPPING', 'TEMU'), ('SHOPPING', 'FASHION'), ('RESTAURANTS', 'WOLT'), ('HEALTH', 'HEALTH CARE'),
    ('TRAVEL', 'HOSPITALITY'),
    ('TRANSFER', 'REVOLUT')              -- card top-ups of one's own Revolut account
) AS k(code, pattern)
JOIN category c ON c.code = k.code
ON CONFLICT ON CONSTRAINT category_rule_uq DO NOTHING;
