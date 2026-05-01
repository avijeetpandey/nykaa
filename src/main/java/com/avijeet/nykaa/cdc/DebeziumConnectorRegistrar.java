package com.avijeet.nykaa.cdc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Registers the Debezium PostgreSQL connector via the Kafka Connect REST API
 * once the application is fully started. Idempotent: a 409 from Connect means
 * the connector already exists and is treated as success.
 *
 * Fails gracefully — if Debezium Connect is unreachable (e.g. during tests or
 * local dev without Docker), the app continues; only a warning is logged.
 */
@Component
@Slf4j
public class DebeziumConnectorRegistrar {

    private static final String CONNECTOR_NAME = "nykaa-products-connector";

    @Value("${app.debezium.connector-url:http://localhost:8083}")
    private String debeziumConnectorUrl;

    @Value("${app.debezium.postgres.host:localhost}")
    private String postgresHost;

    @Value("${app.debezium.postgres.port:5432}")
    private String postgresPort;

    @Value("${app.debezium.postgres.database:nykaa}")
    private String postgresDatabase;

    @Value("${spring.datasource.username:postgres}")
    private String postgresUser;

    @Value("${spring.datasource.password:postgres}")
    private String postgresPassword;

    private final RestTemplate restTemplate = new RestTemplate();

    @EventListener(ApplicationReadyEvent.class)
    public void registerConnector() {
        String url = debeziumConnectorUrl + "/connectors";
        log.info("[CDC] Registering Debezium connector '{}' at {}", CONNECTOR_NAME, url);

        Map<String, Object> payload = Map.of(
                "name", CONNECTOR_NAME,
                "config", Map.ofEntries(
                        Map.entry("connector.class", "io.debezium.connector.postgresql.PostgresConnector"),
                        Map.entry("database.hostname", postgresHost),
                        Map.entry("database.port", postgresPort),
                        Map.entry("database.user", postgresUser),
                        Map.entry("database.password", postgresPassword),
                        Map.entry("database.dbname", postgresDatabase),
                        Map.entry("topic.prefix", "nykaa"),
                        Map.entry("table.include.list", "public.products"),
                        Map.entry("plugin.name", "pgoutput"),
                        Map.entry("publication.autocreate.mode", "filtered"),
                        Map.entry("slot.name", "nykaa_products_slot"),
                        Map.entry("decimal.handling.mode", "double"),
                        // Route nykaa.public.products → nykaa.product.changes
                        Map.entry("transforms", "route"),
                        Map.entry("transforms.route.type",
                                "org.apache.kafka.connect.transforms.RegexRouter"),
                        Map.entry("transforms.route.regex", "nykaa\\.public\\.products"),
                        Map.entry("transforms.route.replacement", "nykaa.product.changes"),
                        // Ensure schemas are not embedded (docker-compose already sets this,
                        // but specifying here makes the connector self-contained)
                        Map.entry("key.converter.schemas.enable", "false"),
                        Map.entry("value.converter.schemas.enable", "false")
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    String.class
            );
            log.info("[CDC] Connector registered successfully. HTTP {}", response.getStatusCode());

        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                log.info("[CDC] Connector '{}' already exists — skipping registration.", CONNECTOR_NAME);
            } else {
                log.error("[CDC] Connector registration failed with HTTP {}: {}",
                        ex.getStatusCode(), ex.getResponseBodyAsString());
            }
        } catch (RestClientException ex) {
            log.warn("[CDC] Debezium Connect not reachable at '{}' — connector not registered. " +
                    "Start Debezium and it will be registered on next app restart. Cause: {}",
                    debeziumConnectorUrl, ex.getMessage());
        }
    }
}
