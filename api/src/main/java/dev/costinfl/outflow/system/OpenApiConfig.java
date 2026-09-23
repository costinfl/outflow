package dev.costinfl.outflow.system;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fixed metadata and a relative server URL, so the generated spec is deterministic and committable. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI outflowOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Outflow API").version("v1"))
                .servers(List.of(new Server().url("/")));
    }
}
