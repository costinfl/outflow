package dev.costinfl.outflow.recurring;

/**
 * Seven months of one account with known recurring payments (plan M4 acceptance): fixed (Netflix), variable (Enel),
 * month end (Orange), one missed month (World Class, no April), and a plan next to one-off purchases (eMAG). A monthly
 * savings transfer, monthly cash withdrawals, Lidl shopping and a refund are not subscriptions.
 */
public final class RecurringFixtures {

    private RecurringFixtures() {}

    public static final String LEDGER = """
            Date,Description,Amount,Currency
            2026-01-15,NETFLIX.COM,-49.99,RON
            2026-02-16,NETFLIX.COM,-49.99,RON
            2026-03-16,NETFLIX.COM,-49.99,RON
            2026-04-15,NETFLIX.COM,-49.99,RON
            2026-05-15,NETFLIX.COM,-49.99,RON
            2026-06-15,NETFLIX.COM,-49.99,RON
            2026-07-15,NETFLIX.COM,-49.99,RON
            2026-01-08,ENEL ENERGIE,-210.01,RON
            2026-02-09,ENEL ENERGIE,-185.50,RON
            2026-03-08,ENEL ENERGIE,-199.90,RON
            2026-04-10,ENEL ENERGIE,-234.00,RON
            2026-05-08,ENEL ENERGIE,-205.00,RON
            2026-06-09,ENEL ENERGIE,-220.10,RON
            2025-12-31,ORANGE ROMANIA,-65.00,RON
            2026-01-31,ORANGE ROMANIA,-65.00,RON
            2026-03-02,ORANGE ROMANIA,-65.00,RON
            2026-03-31,ORANGE ROMANIA,-65.00,RON
            2026-04-30,ORANGE ROMANIA,-65.00,RON
            2026-05-31,ORANGE ROMANIA,-65.00,RON
            2026-06-30,ORANGE ROMANIA,-65.00,RON
            2026-01-03,WORLD CLASS,-250.00,RON
            2026-02-03,WORLD CLASS,-250.00,RON
            2026-03-03,WORLD CLASS,-250.00,RON
            2026-05-04,WORLD CLASS,-250.00,RON
            2026-06-03,WORLD CLASS,-250.00,RON
            2026-07-03,WORLD CLASS,-250.00,RON
            2026-01-05,EMAG.RO,-29.99,RON
            2026-01-19,EMAG.RO,-120.00,RON
            2026-02-05,EMAG.RO,-29.99,RON
            2026-03-05,EMAG.RO,-29.99,RON
            2026-03-22,EMAG.RO,-450.00,RON
            2026-04-06,EMAG.RO,-29.99,RON
            2026-05-05,EMAG.RO,-29.99,RON
            2026-06-05,EMAG.RO,-29.99,RON
            2026-01-01,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-02-01,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-03-01,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-04-01,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-05-01,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-06-01,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-01-10,RETRAGERE NUMERAR ATM,-500.00,RON
            2026-02-10,RETRAGERE NUMERAR ATM,-500.00,RON
            2026-03-10,RETRAGERE NUMERAR ATM,-500.00,RON
            2026-04-10,RETRAGERE NUMERAR ATM,-500.00,RON
            2026-05-11,RETRAGERE NUMERAR ATM,-500.00,RON
            2026-06-10,RETRAGERE NUMERAR ATM,-500.00,RON
            2026-01-02,CUMPARARE POS LIDL,-150.00,RON
            2026-01-09,CUMPARARE POS LIDL,-160.00,RON
            2026-01-20,CUMPARARE POS LIDL,-155.00,RON
            2026-02-03,CUMPARARE POS LIDL,-170.00,RON
            2026-02-17,CUMPARARE POS LIDL,-165.00,RON
            2026-03-01,CUMPARARE POS LIDL,-158.00,RON
            2026-03-04,CUMPARARE POS LIDL,40.00,RON
            2026-01-25,INCASARE SALARIU ACME SRL,5000.00,RON
            2026-02-25,INCASARE SALARIU ACME SRL,5000.00,RON
            2026-03-25,INCASARE SALARIU ACME SRL,5000.00,RON
            2026-04-24,INCASARE SALARIU ACME SRL,5000.00,RON
            """;
}
