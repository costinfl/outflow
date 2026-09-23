package dev.costinfl.outflow;

import org.springframework.boot.SpringApplication;

/** Runs the API against a throwaway Testcontainers Postgres: {@code ./mvnw -pl api spring-boot:test-run}. */
public class TestOutflowApplication {

    public static void main(String[] args) {
        SpringApplication.from(OutflowApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
