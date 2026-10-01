package dev.costinfl.outflow.ingest.parse.camt;

import dev.costinfl.outflow.ingest.parse.AccountHint;
import dev.costinfl.outflow.ingest.parse.DetectionScore;
import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * ISO 20022 CAMT.053 bank-to-customer statement (DESIGN: Statement parsing, "Preferred formats: CAMT.053"). Elements are
 * matched by local name, so the versions 001.02 to 001.13 read alike. One row per entry ({@code Ntry}):
 * <ul>
 *   <li>booking date ({@code BookgDt/Dt} or the date of {@code DtTm}), value date, the amount in exact minor units,
 *       negative for {@code DBIT};</li>
 *   <li>status {@code BOOK} posted, {@code PDNG} pending (it may reappear booked with another date or amount),
 *       {@code INFO} skipped: information only, not money that moved;</li>
 *   <li>the counterparty is the creditor of money out and the debtor of money in. The description is its name, its
 *       IBAN (so own-account transfers can be recognised) and the remittance text;</li>
 *   <li>the bank reference ({@code AcctSvcrRef}) identifies booked entries only, so a pending entry can still be
 *       superseded by its booked version.</li>
 * </ul>
 * The XML is parsed with DTDs and external entities disabled: a statement never needs them.
 */
public final class Camt053Parser implements StatementParser {

    public static final String ID = "camt053-v1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "ISO 20022 CAMT.053 statement (XML)";
    }

    @Override
    public DetectionScore detect(FileSample sample) {
        String head = new String(sample.head(), StandardCharsets.UTF_8);
        if (head.contains("urn:iso:std:iso:20022:tech:xsd:camt.053")) {
            return new DetectionScore(0.95, "CAMT.053 namespace");
        }
        if (head.contains("<BkToCstmrStmt") || head.contains(":BkToCstmrStmt")) {
            return new DetectionScore(0.85, "BkToCstmrStmt element without the CAMT.053 namespace");
        }
        return DetectionScore.none("not a CAMT.053 statement");
    }

    @Override
    public ParsedStatement parse(InputStream in) throws IOException {
        Element root;
        try {
            root = secureFactory().newDocumentBuilder().parse(in).getDocumentElement();
        } catch (SAXException | ParserConfigurationException e) {
            throw new StatementParseException("Not a readable CAMT.053 XML file: " + e.getMessage(), e);
        }
        Element report = child(root, "BkToCstmrStmt")
                .orElseThrow(() -> new StatementParseException("No BkToCstmrStmt: not a CAMT.053 statement"));
        var rows = new ArrayList<ParsedRow>();
        Optional<AccountHint> hint = Optional.empty();
        for (Element stmt : children(report, "Stmt")) {
            Optional<AccountHint> stmtHint = accountHint(stmt);
            if (hint.isPresent() && stmtHint.isPresent()
                    && !hint.get().iban().equals(stmtHint.get().iban())) {
                throw new StatementParseException("The file holds statements of two accounts; upload one account per file");
            }
            hint = hint.or(() -> stmtHint);
            for (Element entry : children(stmt, "Ntry")) {
                entry(rows.size() + 1, entry).ifPresent(rows::add);
            }
        }
        return ParsedStatement.of(ID, hint, rows);
    }

    private static Optional<AccountHint> accountHint(Element stmt) {
        var acct = child(stmt, "Acct");
        Optional<Iban> iban = acct.flatMap(a -> text(a, "Id", "IBAN")).flatMap(Iban::parse);
        Optional<String> currency = acct.flatMap(a -> text(a, "Ccy"));
        return iban.map(i -> new AccountHint(i, currency));
    }

    /** One entry as a row, or empty for an INFO entry. */
    private static Optional<ParsedRow> entry(int rowNo, Element ntry) {
        String status = text(ntry, "Sts", "Cd").or(() -> text(ntry, "Sts")).orElse("BOOK").strip();
        if (status.equals("INFO")) {
            return Optional.empty();
        }
        boolean pending = status.equals("PDNG");
        var payload = new LinkedHashMap<String, String>();
        Element amt = child(ntry, "Amt").orElseThrow(() -> StatementParseException.atRow(rowNo, "entry without Amt"));
        String currency = amt.getAttribute("Ccy");
        String amount = amt.getTextContent().strip();
        String direction = text(ntry, "CdtDbtInd").orElseThrow(() -> StatementParseException.atRow(rowNo, "entry without CdtDbtInd"));
        if (!direction.equals("DBIT") && !direction.equals("CRDT")) {
            throw StatementParseException.atRow(rowNo, "CdtDbtInd must be DBIT or CRDT, not " + direction);
        }
        String booking = date(ntry, "BookgDt").orElseThrow(() -> StatementParseException.atRow(rowNo, "entry without BookgDt"));
        Optional<String> value = date(ntry, "ValDt");
        Optional<Element> tx = child(ntry, "NtryDtls").flatMap(d -> child(d, "TxDtls"));
        boolean out = direction.equals("DBIT");
        Optional<Element> parties = tx.flatMap(t -> child(t, "RltdPties"));
        // The other party: who was paid for money out, who paid for money in ("Pty" wraps the name from 001.08 on).
        Optional<String> name = parties.flatMap(p -> partyName(p, out ? "Cdtr" : "Dbtr"));
        Optional<String> partyIban = parties.flatMap(p -> text(p, out ? "CdtrAcct" : "DbtrAcct", "Id", "IBAN"));
        String remittance = tx.map(t -> children(t, "RmtInf").stream()
                .flatMap(r -> children(r, "Ustrd").stream()).map(e -> e.getTextContent().strip())
                .filter(s -> !s.isEmpty()).collect(Collectors.joining(" "))).orElse("");
        Optional<String> additional = text(ntry, "AddtlNtryInf");
        Optional<String> reference = text(ntry, "AcctSvcrRef").or(() -> tx.flatMap(t -> text(t, "Refs", "AcctSvcrRef")));

        payload.put("BookgDt", booking);
        value.ifPresent(v -> payload.put("ValDt", v));
        payload.put("Amt", amount);
        payload.put("Ccy", currency);
        payload.put("CdtDbtInd", direction);
        payload.put("Sts", status);
        text(ntry, "RvslInd").ifPresent(v -> payload.put("RvslInd", v));
        reference.ifPresent(v -> payload.put("AcctSvcrRef", v));
        name.ifPresent(v -> payload.put(out ? "Cdtr" : "Dbtr", v));
        partyIban.ifPresent(v -> payload.put(out ? "CdtrIBAN" : "DbtrIBAN", v));
        if (!remittance.isEmpty()) {
            payload.put("Ustrd", remittance);
        }
        additional.ifPresent(v -> payload.put("AddtlNtryInf", v));

        String description = java.util.stream.Stream.of(name.orElse(""), partyIban.orElse(""),
                        remittance.isEmpty() ? additional.orElse("") : remittance)
                .filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
        if (description.isBlank()) {
            description = out ? "DEBIT" : "CREDIT";
        }
        try {
            long minor = minorUnits(amount, currency);
            return Optional.of(new ParsedRow(rowNo, payload, LocalDate.parse(booking), value.map(LocalDate::parse),
                    out ? -minor : minor, currency, description,
                    pending ? Optional.empty() : reference.filter(r -> !r.isBlank()), name, pending));
        } catch (DateTimeParseException e) {
            throw StatementParseException.atRow(rowNo, "bad date: " + e.getParsedString());
        }
    }

    /** "12.34" in EUR → 1234; more decimals than the currency has, a sign or a bad currency fail the file. */
    static long minorUnits(String amount, String currencyCode) {
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new StatementParseException("Unknown currency '" + currencyCode + "'");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new StatementParseException("Bad amount '" + amount + "'");
        }
        if (value.signum() < 0) {
            throw new StatementParseException("Amounts are unsigned in CAMT.053 (CdtDbtInd gives the sign): " + amount);
        }
        int digits = Math.max(0, currency.getDefaultFractionDigits());
        if (value.stripTrailingZeros().scale() > digits) {
            throw new StatementParseException("More decimals than " + currencyCode + " has: " + amount);
        }
        return value.movePointRight(digits).longValueExact();
    }

    private static Optional<String> date(Element parent, String name) {
        return child(parent, name).flatMap(d -> text(d, "Dt").or(() -> text(d, "DtTm").map(t -> t.substring(0, 10))));
    }

    private static Optional<String> partyName(Element parties, String role) {
        return child(parties, role).flatMap(p -> text(p, "Nm").or(() -> text(p, "Pty", "Nm")));
    }

    /** The text of the element at {@code path} below {@code parent}, stripped; empty when absent or blank. */
    private static Optional<String> text(Element parent, String... path) {
        Optional<Element> e = Optional.of(parent);
        for (String name : path) {
            e = e.flatMap(p -> child(p, name));
        }
        return e.map(x -> x.getTextContent().strip()).filter(s -> !s.isEmpty());
    }

    private static Optional<Element> child(Element parent, String localName) {
        var all = children(parent, localName);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    private static List<Element> children(Element parent, String localName) {
        var found = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                found.add(e);
            }
        }
        return found;
    }

    /** Namespace-aware, with DTDs, external entities and XInclude off (XXE). */
    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f;
    }
}
