package com.owo.banking_ledger.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bankingLedgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Banking Ledger API")
                        .version("0.0.1")
                        .description("""
                                Banking ledger API for customer accounts, deposits,
                                withdrawals, transfers, and paginated ledger entries.
                                """)
                        .contact(new Contact()
                                .name("Banking Ledger"))
                        .license(new License()
                                .name("Private project")))
                .addServersItem(new Server()
                        .url("http://localhost:8080")
                        .description("Local development"));
    }
}
