package com.bowt.backend.orderprocessing.infrastructure.rest.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DOC-4. Swagger UI at /swagger-ui.html in non-production profiles.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI orderProcessingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Processing API")
                        .description("High-performance order management system")
                        .version("v1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"))
                .components(new Components()
                        .addSecuritySchemes("apiKey",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-API-Key")));
    }
}