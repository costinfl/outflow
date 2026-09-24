package dev.costinfl.outflow.ingest.parse.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    private static List<List<String>> read(String text, char delimiter) throws IOException {
        return CsvReader.read(new StringReader(text), delimiter);
    }

    @Test
    void plainRecordsWithAnyLineEnding() throws IOException {
        assertThat(read("a,b\r\nc,d\ne,f\rg,h", ','))
                .containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e", "f"), List.of("g", "h"));
    }

    @Test
    void quotedCellsKeepDelimitersQuotesAndLineBreaks() throws IOException {
        assertThat(read("\"a;1\";\"say \"\"hi\"\"\";\"two\r\nlines\"\n", ';'))
                .containsExactly(List.of("a;1", "say \"hi\"", "two\r\nlines"));
    }

    @Test
    void emptyCellsAndBlankLinesArePreserved() throws IOException {
        assertThat(read("a,,c\n\n,\n", ','))
                .containsExactly(List.of("a", "", "c"), List.of(""), List.of("", ""));
    }

    @Test
    void byteOrderMarkIsStripped() throws IOException {
        assertThat(read("﻿Date,Amount\n", ',')).containsExactly(List.of("Date", "Amount"));
    }

    @Test
    void quotedEmptyCellAtEndOfFile() throws IOException {
        assertThat(read("a,\"\"", ',')).containsExactly(List.of("a", ""));
    }

    @Test
    void unterminatedQuoteFailsInsteadOfSwallowingTheRest() {
        assertThatThrownBy(() -> read("a,\"b\nc,d\n", ',')).hasMessageContaining("unterminated");
    }
}
