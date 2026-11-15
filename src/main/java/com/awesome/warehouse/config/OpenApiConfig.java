package com.awesome.warehouse.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    /** The backend's service token on the stock ledger API ({@code BACKEND_SERVICE_TOKEN}). */
    public static final String BACKEND_SERVICE_TOKEN = "backendServiceToken";

    @Bean
    OpenAPI warehouseOpenApi() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes(BACKEND_SERVICE_TOKEN, new SecurityScheme()
                                .name(BACKEND_SERVICE_TOKEN)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("The backend's BACKEND_SERVICE_TOKEN (caller BACKEND), "
                                        + "only for /internal/v1/stock")))
                .info(new Info()
                        .title("Warehouse Service API")
                        .version("1.0")
                        .description("Shipments, returns (RMA), suppliers, purchase orders and the stock ledger. "
                                + "Uses the access tokens issued by the backend.")
                        .license(new License().name("Apache 2.0").url("http://springdoc.org")));
    }
}
