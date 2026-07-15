package com.ludovictemgoua.imdb;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		// Pinned, not :latest - the floating tag makes a passing build today no guarantee of a
		// passing build tomorrow if upstream ships a breaking change. 0.29.0 confirmed current via
		// the Docker Hub API (its digest matches :latest's at the time of pinning), not guessed.
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.29.0"));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		// redis:7-alpine, not :latest - also matches the version docker-compose.yaml actually runs in
		// dev/prod (README's External Services table), so the test double and the real deployment
		// target are the same major version instead of silently drifting apart.
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

}
