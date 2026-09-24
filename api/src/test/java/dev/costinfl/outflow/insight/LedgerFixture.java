package dev.costinfl.outflow.insight;

/** A small ledger whose every home-screen figure was computed by hand; shared by insight and transaction tests. */
public final class LedgerFixture {

    private LedgerFixture() {}

    /**
     * Baseline: Lidl 1,000 / 1,200 / 1,100 RON in Dec–Feb (average 1,100). March, computed by hand:
     * spent = groceries 450 (300 + 200 − 50 refund) + fuel 250 + utilities 210.01 + shopping 120 + restaurants 100
     * + transport 80 + subscriptions 49.99 + uncategorized 40 = 1,300.00. The 1,000 savings transfer, 5,000 salary
     * and an unidentified +30 inflow are not spending.
     */
    public static final String LEDGER = """
            Date,Description,Amount,Currency
            2025-12-05,CUMPARARE POS LIDL,-1000.00,RON
            2026-01-05,CUMPARARE POS LIDL,-1200.00,RON
            2026-02-05,CUMPARARE POS LIDL,-1100.00,RON
            2026-03-02,CUMPARARE POS LIDL,-300.00,RON
            2026-03-09,CUMPARARE POS LIDL,-200.00,RON
            2026-03-10,CUMPARARE POS LIDL,50.00,RON
            2026-03-03,CUMPARARE POS STARBUCKS,-100.00,RON
            2026-03-04,BOLT.EU/R/1,-80.00,RON
            2026-03-05,CUMPARARE POS OMV,-250.00,RON
            2026-03-06,NETFLIX.COM,-49.99,RON
            2026-03-07,PLATA CARD EMAG.RO,-120.00,RON
            2026-03-08,ENEL ENERGIE FACTURA 1,-210.01,RON
            2026-03-11,CUMPARARE POS ZZ WIDGETS,-40.00,RON
            2026-03-10,INCASARE SALARIU ACME SRL,5000.00,RON
            2026-03-12,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-03-13,INCASARE ZZ INFLOW,30.00,RON
            """;
}
