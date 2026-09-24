package dev.costinfl.outflow.ingest.parse;

/** Confidence in [0, 1] that a parser understands a file, with the reason for the upload screen and logs. */
public record DetectionScore(double value, String reason) implements Comparable<DetectionScore> {

    public DetectionScore {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException("score must be in [0, 1]: " + value);
        }
    }

    public static DetectionScore none(String reason) {
        return new DetectionScore(0, reason);
    }

    @Override
    public int compareTo(DetectionScore other) {
        return Double.compare(value, other.value);
    }
}
