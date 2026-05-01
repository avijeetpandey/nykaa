package com.avijeet.nykaa.cart;

import com.avijeet.nykaa.dto.cart.CartItemData;
import com.avijeet.nykaa.dto.cart.CartResponseDto;
import com.avijeet.nykaa.entities.product.Product;
import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import com.avijeet.nykaa.exception.ProductNotFoundException;
import com.avijeet.nykaa.exception.cart.CartNotFoundException;
import com.avijeet.nykaa.repository.product.ProductRepository;
import com.avijeet.nykaa.service.cart.CartService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CartServiceIntegrationTest {

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

    @Autowired private CartService cartService;
    @Autowired private ProductRepository productRepository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    private static final Long USER_ID = 1L;
    private Product savedProduct;

    @BeforeEach
    void setUp() {
        savedProduct = productRepository.save(Product.builder()
                .name("Test Lipstick")
                .brand(Brand.GUCCI)
                .category(Category.MAKEUP)
                .price(999.0)
                .stockQuantity(50)
                .build());
    }

    @AfterEach
    void tearDown() {
        cartService.clearCart(USER_ID);
        productRepository.deleteAll();
    }

    @Test
    void addItemCreatesCartWithCorrectData() {
        CartResponseDto cart = cartService.addItem(USER_ID, savedProduct.getId(), 2);

        assertThat(cart.userId()).isEqualTo(USER_ID);
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).getProductId()).isEqualTo(savedProduct.getId());
        assertThat(cart.items().get(0).getProductName()).isEqualTo("Test Lipstick");
        assertThat(cart.items().get(0).getPrice()).isEqualTo(999.0);
        assertThat(cart.items().get(0).getQuantity()).isEqualTo(2);
        assertThat(cart.totalAmount()).isEqualTo(1998.0);
    }

    @Test
    void addItemAccumulatesQuantityForSameProduct() {
        cartService.addItem(USER_ID, savedProduct.getId(), 2);
        CartResponseDto cart = cartService.addItem(USER_ID, savedProduct.getId(), 3);

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).getQuantity()).isEqualTo(5);
        assertThat(cart.totalAmount()).isEqualTo(4995.0);
    }

    @Test
    void addItemWithMultipleProductsTracksEachSeparately() {
        Product second = productRepository.save(Product.builder()
                .name("Perfume")
                .brand(Brand.PRADA)
                .category(Category.PERFUME)
                .price(2500.0)
                .stockQuantity(20)
                .build());

        cartService.addItem(USER_ID, savedProduct.getId(), 1);
        CartResponseDto cart = cartService.addItem(USER_ID, second.getId(), 2);

        assertThat(cart.items()).hasSize(2);
        assertThat(cart.totalAmount()).isEqualTo(999.0 + 2500.0 * 2);

        productRepository.delete(second);
    }

    @Test
    void getCartThrowsWhenNoCartExists() {
        assertThatThrownBy(() -> cartService.getCart(99L))
                .isInstanceOf(CartNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getCartReturnsExistingCart() {
        cartService.addItem(USER_ID, savedProduct.getId(), 3);
        CartResponseDto cart = cartService.getCart(USER_ID);

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    void removeItemDeletesOnlyThatProduct() {
        Product second = productRepository.save(Product.builder()
                .name("Lotion")
                .brand(Brand.MUSCLEBLAZE)
                .category(Category.LOTION)
                .price(500.0)
                .stockQuantity(30)
                .build());

        cartService.addItem(USER_ID, savedProduct.getId(), 1);
        cartService.addItem(USER_ID, second.getId(), 2);
        cartService.removeItem(USER_ID, savedProduct.getId());

        CartResponseDto cart = cartService.getCart(USER_ID);
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).getProductId()).isEqualTo(second.getId());

        productRepository.delete(second);
    }

    @Test
    void clearCartRemovesAllItems() {
        cartService.addItem(USER_ID, savedProduct.getId(), 2);
        assertThat(cartService.isCartEmpty(USER_ID)).isFalse();

        cartService.clearCart(USER_ID);
        assertThat(cartService.isCartEmpty(USER_ID)).isTrue();
    }

    @Test
    void getCartItemsReturnsTypedList() {
        cartService.addItem(USER_ID, savedProduct.getId(), 4);
        List<CartItemData> items = cartService.getCartItems(USER_ID);

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).isInstanceOf(CartItemData.class);
        assertThat(items.get(0).getQuantity()).isEqualTo(4);
    }

    @Test
    void addItemThrowsForNonExistentProduct() {
        assertThatThrownBy(() -> cartService.addItem(USER_ID, 999999L, 1))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
