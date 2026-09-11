package com.owo.banking_ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Gives every {@code @SpringBootTest} its own throwaway PostgreSQL container so
 * runs never touch the local development database on port 5433. Flyway migrates
 * the container on context startup, so a test run always starts from a schema
 * built by the same migrations the application ships.
 *
 * <p>The image tag matches compose.yaml, keeping tests on the same PostgreSQL
 * version the application runs against.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17");
	}
}
