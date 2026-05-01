package com.avijeet.nykaa.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nykaa_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static final GenericContainer<?> elasticsearch = new GenericContainer<>(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.0.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.security.enrollment.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200));
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("management.tracing.sampling.probability", () -> "0.0");
        registry.add("app.debezium.connector-url", () -> "http://localhost:19999");
    }

    @Autowired private RedisRateLimiter rateLimiter;
    @Autowired private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanKeys() {
        stringRedisTemplate.delete(stringRedisTemplate.keys("nykaa:ratelimit:*"));
    }

    @Test
    void withinCapacity_allRequestsAllowed() {
        int capacity = 5;
        for (int i = 0; i < capacity; i++) {
            boolean allowed = rateLimiter.allowRequest("user:test@nykaa.com", "test.endpoint", capacity, 60);
            assertThat(allowed).as("Request %d should be allowed", i + 1).isTrue();
        }
    }

    @Test
    void exceedingCapacity_requestsDenied() {
        int capacity = 3;
        // Exhaust bucket
        for (int i = 0; i < capacity; i++) {
            rateLimiter.allowRequest("user:burst@nykaa.com", "test.burst", capacity, 60);
        }
        // Next request must be denied
        boolean denied = rateLimiter.allowRequest("user:burst@nykaa.com", "test.burst", capacity, 60);
        assertThat(denied).isFalse();
    }

    @Test
    void differentIdentifiers_independentBuckets() {
        int capacity = 2;
        // Exhaust user A
        rateLimiter.allowRequest("user:a@nykaa.com", "test.isolation", capacity, 60);
        rateLimiter.allowRequest("user:a@nykaa.com", "test.isolation", capacity, 60);
        boolean aThird = rateLimiter.allowRequest("user:a@nykaa.com", "test.isolation", capacity, 60);
        assertThat(aThird).isFalse();

        // User B's bucket is unaffected
        boolean bFirst = rateLimiter.allowRequest("user:b@nykaa.com", "test.isolation", capacity, 60);
        assertThat(bFirst).isTrue();
    }

    @Test
    void differentEndpoints_independentBuckets() {
        int capacity = 2;
        // Exhaust endpoint A for same user
        rateLimiter.allowRequest("user:shared@nykaa.com", "endpoint.a", capacity, 60);
        rateLimiter.allowRequest("user:shared@nykaa.com", "endpoint.a", capacity, 60);
        boolean aThird = rateLimiter.allowRequest("user:shared@nykaa.com", "endpoint.a", capacity, 60);
        assertThat(aThird).isFalse();

        // Endpoint B's bucket for the same user is still full
        boolean bFirst = rateLimiter.allowRequest("user:shared@nykaa.com", "endpoint.b", capacity, 60);
        assertThat(bFirst).isTrue();
    }

    @Test
    void tokenRefillAfterDelay_allowsRequestsAgain() throws InterruptedException {
        int capacity = 1;
        // Exhaust the single-token bucket
        rateLimiter.allowRequest("user:refill@nykaa.com", "test.refill", capacity, 60);
        boolean denied = rateLimiter.allowRequest("user:refill@nykaa.com", "test.refill", capacity, 60);
        assertThat(denied).isFalse();

        // Wait long enough for 1 token to refill: 60_000ms / 60 tokens-per-minute = 1000ms per token
        Thread.sleep(1100);

        boolean allowed = rateLimiter.allowRequest("user:refill@nykaa.com", "test.refill", capacity, 60);
        assertThat(allowed).isTrue();
    }

    @Test
    void highCapacity_allRequestsServedWithinBurst() {
        int capacity = 100;
        long allowed = 0;
        for (int i = 0; i < capacity; i++) {
            if (rateLimiter.allowRequest("user:high@nykaa.com", "test.high", capacity, capacity)) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(capacity);
    }
}
