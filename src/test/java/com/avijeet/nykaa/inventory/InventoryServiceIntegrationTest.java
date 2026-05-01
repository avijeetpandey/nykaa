package com.avijeet.nykaa.inventory;

import com.avijeet.nykaa.entities.product.Product;
import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import com.avijeet.nykaa.exception.inventory.InsufficientStockException;
import com.avijeet.nykaa.exception.inventory.InventoryLockException;
import com.avijeet.nykaa.repository.product.ProductRepository;
import com.avijeet.nykaa.service.inventory.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryServiceIntegrationTest {

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
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

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
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.admin.fail-fast", () -> "false");
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    @Autowired private InventoryService inventoryService;
    @Autowired private ProductRepository productRepository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    private Product product;

    @BeforeEach
    void setUp() {
        product = productRepository.save(Product.builder()
                .name("Test Perfume")
                .brand(Brand.TOMFORD)
                .category(Category.PERFUME)
                .price(3000.0)
                .stockQuantity(10)
                .build());
    }

    @AfterEach
    void tearDown() {
        productRepository.deleteAll();
        redisTemplate.delete("nykaa:lock:inventory:" + product.getId());
    }

    @Test
    void reserveStockDeductsCorrectly() {
        inventoryService.reserveStock(product.getId(), 3);

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        assertThat(refreshed.getStockQuantity()).isEqualTo(7);
    }

    @Test
    void reserveStockThrowsWhenStockInsufficient() {
        assertThatThrownBy(() -> inventoryService.reserveStock(product.getId(), 20))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void stockRemainsUnchangedAfterFailedReservation() {
        try {
            inventoryService.reserveStock(product.getId(), 999);
        } catch (InsufficientStockException ignored) {}

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        assertThat(refreshed.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void rollbackStockRestoresDeductedUnits() {
        inventoryService.reserveStock(product.getId(), 4);
        inventoryService.rollbackStock(product.getId(), 4);

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        assertThat(refreshed.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void partialRollbackIsAccurate() {
        inventoryService.reserveStock(product.getId(), 6);
        inventoryService.rollbackStock(product.getId(), 3);

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        assertThat(refreshed.getStockQuantity()).isEqualTo(7);
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        // 5 threads each try to reserve 3 units from stock of 10.
        // Only floor(10/3) = 3 should succeed; total deducted ≤ 10.
        int threadCount = 5;
        int reserveQty = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                try {
                    inventoryService.reserveStock(product.getId(), reserveQty);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException | InventoryLockException e) {
                    failCount.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        for (Future<Void> f : futures) f.get();

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        int deducted = 10 - refreshed.getStockQuantity();

        assertThat(deducted).isLessThanOrEqualTo(10);
        assertThat(deducted).isEqualTo(successCount.get() * reserveQty);
        assertThat(refreshed.getStockQuantity()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reserveEntireStockLeavesZeroRemaining() {
        inventoryService.reserveStock(product.getId(), 10);

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        assertThat(refreshed.getStockQuantity()).isZero();
    }

    @Test
    void reserveAfterFullDepletionThrowsInsufficientStock() {
        inventoryService.reserveStock(product.getId(), 10);

        assertThatThrownBy(() -> inventoryService.reserveStock(product.getId(), 1))
                .isInstanceOf(InsufficientStockException.class);
    }
}
