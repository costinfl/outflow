package dev.costinfl.outflow.ingest.parse;

import dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser;
import dev.costinfl.outflow.ingest.parse.csv.CsvProfileLoader;
import dev.costinfl.outflow.ingest.parse.ing.IngRoCsvParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Registers the built-in bank parsers plus one CSV parser per YAML profile found at {@code outflow.parsers.locations}
 * (default: the profiles shipped in {@code classpath:parsers/}). Add a directory, e.g.
 * {@code classpath:parsers/*.yml,file:/config/parsers/*.yml}, to plug in a bank without rebuilding.
 */
@Configuration
public class ParserConfig {

    @Bean
    StatementDetector statementDetector(
            @Value("${outflow.parsers.locations:classpath:parsers/*.yml}") String[] locations) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        // Bank formats that a column mapping cannot express get a parser class; the rest are YAML profiles.
        var parsers = new ArrayList<StatementParser>(List.of(new IngRoCsvParser()));
        for (String location : locations) {
            for (Resource yaml : resolver.getResources(location.strip())) {
                try (var in = yaml.getInputStream()) {
                    parsers.add(new ConfigurableCsvParser(CsvProfileLoader.load(in, yaml.getDescription())));
                }
            }
        }
        return new StatementDetector(List.copyOf(parsers));
    }
}
