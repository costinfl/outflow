package dev.costinfl.outflow.ingest.parse;

import java.util.Optional;

/**
 * Account identification found in a file. The plain IBAN lives only in memory during an import: it is hashed and
 * masked before anything is stored, and {@link #toString()} never prints it.
 */
public record AccountHint(Iban iban, Optional<String> currency) {

    @Override
    public String toString() {
        return "AccountHint[iban=" + iban.masked() + ", currency=" + currency.orElse("?") + "]";
    }
}
