package dev.costinfl.outflow.ingest.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ParserConfigTest {

    @Autowired
    StatementDetector detector;

    @Test
    void shippedProfilesAreRegistered() {
        assertThat(detector.parsers()).extracting(StatementParser::id).contains("generic-csv-v1");
    }
}
