package dev.costinfl.outflow.ingest.parse;

import java.util.Arrays;

/** The first bytes of an uploaded file plus its name: enough to sniff header, delimiter and encoding. */
public record FileSample(String fileName, byte[] head) {

    public static final int SIZE = 64 * 1024;

    public FileSample {
        head = Arrays.copyOf(head, Math.min(head.length, SIZE));
    }

    public static FileSample of(String fileName, byte[] content) {
        return new FileSample(fileName, content);
    }

    @Override
    public byte[] head() {
        return head.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileSample other && fileName.equals(other.fileName) && Arrays.equals(head, other.head);
    }

    @Override
    public int hashCode() {
        return 31 * fileName.hashCode() + Arrays.hashCode(head);
    }

    @Override
    public String toString() {
        return "FileSample[" + fileName + ", " + head.length + " bytes]";
    }
}
