package dev.costinfl.outflow.ingest.parse.csv;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads a {@link CsvProfile} from YAML. Unknown keys are errors, so a typo never silently drops a column. */
public final class CsvProfileLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CsvProfileLoader() {}

    public static CsvProfile load(InputStream yaml, String source) throws IOException {
        Object tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(tree instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(source + ": expected a YAML mapping");
        }
        try {
            return MAPPER.convertValue(tree, CsvProfile.class);
        } catch (IllegalArgumentException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            throw new IllegalArgumentException(source + ": " + root.getMessage(), e);
        }
    }
}
