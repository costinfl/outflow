package dev.costinfl.outflow.ingest.parse.csv;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reader: configurable delimiter, double-quote quoting with {@code ""} escapes, quoted delimiters and
 * line breaks, CRLF/LF/CR line ends, leading BOM stripped. Every record is returned, including blank lines (as a single
 * empty cell), so callers keep exact line positions.
 */
public final class CsvReader {

    private static final int QUOTE = '"';

    private CsvReader() {}

    public static List<List<String>> read(Reader in, char delimiter) throws IOException {
        var records = new ArrayList<List<String>>();
        var record = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        boolean cellWasQuoted = false;
        boolean first = true;
        int c;
        while ((c = in.read()) != -1) {
            if (first) {
                first = false;
                if (c == '﻿') {
                    continue;
                }
            }
            if (quoted) {
                if (c == QUOTE) {
                    in.mark(1);
                    int next = in.read();
                    if (next == QUOTE) {
                        cell.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            in.reset();
                        }
                    }
                } else {
                    cell.append((char) c);
                }
            } else if (c == QUOTE && cell.isEmpty() && !cellWasQuoted) {
                quoted = true;
                cellWasQuoted = true;
            } else if (c == delimiter) {
                record.add(cell.toString());
                cell.setLength(0);
                cellWasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    in.mark(1);
                    if (in.read() != '\n') {
                        in.reset();
                    }
                }
                record.add(cell.toString());
                records.add(List.copyOf(record));
                record.clear();
                cell.setLength(0);
                cellWasQuoted = false;
            } else {
                cell.append((char) c);
            }
        }
        if (quoted) {
            throw new IOException("unterminated quoted cell at end of file");
        }
        if (!cell.isEmpty() || !record.isEmpty() || cellWasQuoted) {
            record.add(cell.toString());
            records.add(List.copyOf(record));
        }
        return records;
    }

    public static boolean isBlank(List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }
}
