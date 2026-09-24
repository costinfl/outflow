package dev.costinfl.outflow.ingest.parse;

/** A file (or a row in it) that a parser cannot read. The message is shown to the user; it never contains an IBAN. */
public class StatementParseException extends RuntimeException {

    public StatementParseException(String message) {
        super(message);
    }

    public StatementParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public static StatementParseException atRow(int rowNo, String message) {
        return new StatementParseException("Row " + rowNo + ": " + message);
    }
}
