package dev.costinfl.outflow.ingest.parse;

import java.io.IOException;
import java.io.InputStream;

/** A parser plugin for one bank + format (DESIGN: Statement parsing). Implementations must be stateless. */
public interface StatementParser {

    /** Stable id stored on {@code statement_file.format}, e.g. {@code "generic-csv-v1"}. */
    String id();

    /** Human-readable name for the upload screen's parser override. */
    String displayName();

    /** How well this parser recognises the file, from its first bytes. Must not throw on foreign input. */
    DetectionScore detect(FileSample sample);

    /** Parses the whole file. Fails the file on the first row it cannot read, rather than skipping it. */
    ParsedStatement parse(InputStream in) throws IOException;
}
