package com.avijeet.nykaa;

import com.avijeet.nykaa.entities.auth.UserSession;
import com.avijeet.nykaa.entities.user.User;
import com.avijeet.nykaa.enums.Role;
import com.avijeet.nykaa.repository.auth.UserSessionRepository;
import com.avijeet.nykaa.repository.product.ProductRepository;
import com.avijeet.nykaa.repository.user.UserRepository;
import com.avijeet.nykaa.utils.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AuthIntegrationTests {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        userSessionRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerShouldCreateCustomerHashPasswordAndReturnToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice",
                                  "email": "alice@example.com",
                                  "password": "password123",
                                  "address": "Mumbai"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isError").value(false))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.user.role").value("CUSTOMER"));

        User savedUser = userRepository.findByEmailIgnoreCase("alice@example.com").orElseThrow();
        assertThat(savedUser.getPassword()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", savedUser.getPassword())).isTrue();
        assertThat(savedUser.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(userSessionRepository.countByUserId(savedUser.getId())).isEqualTo(1);
    }

    @Test
    void registerShouldRejectDuplicateEmail() throws Exception {
        userRepository.save(User.builder()
                .name("Alice")
                .email("alice@example.com")
                .password(passwordEncoder.encode("password123"))
                .address("Mumbai")
                .role(Role.CUSTOMER)
                .build());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice Again",
                                  "email": "alice@example.com",
                                  "password": "password123",
                                  "address": "Delhi"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.isError").value(true))
                .andExpect(jsonPath("$.message").value("User already exists with email: alice@example.com"));
    }

    @Test
    void loginShouldReturnTokenForValidCredentials() throws Exception {
        userRepository.save(User.builder()
                .name("Bob")
                .email("bob@example.com")
                .password(passwordEncoder.encode("password123"))
                .address("Pune")
                .role(Role.CUSTOMER)
                .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bob@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isError").value(false))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value("bob@example.com"));

        User savedUser = userRepository.findByEmailIgnoreCase("bob@example.com").orElseThrow();
        assertThat(userSessionRepository.countByUserId(savedUser.getId())).isEqualTo(1);
    }

    @Test
    void loginShouldRejectInvalidCredentials() throws Exception {
        userRepository.save(User.builder()
                .name("Bob")
                .email("bob@example.com")
                .password(passwordEncoder.encode("password123"))
                .address("Pune")
                .role(Role.CUSTOMER)
                .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bob@example.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isError").value(true))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void meShouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isError").value(true))
                .andExpect(jsonPath("$.message").value("Unauthorized access"));
    }

    @Test
    void meShouldReturnAuthenticatedUser() throws Exception {
        User user = userRepository.save(User.builder()
                .name("Carol")
                .email("carol@example.com")
                .password(passwordEncoder.encode("password123"))
                .address("Bengaluru")
                .role(Role.CUSTOMER)
                .build());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isError").value(false))
                .andExpect(jsonPath("$.data.email").value("carol@example.com"))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"));
    }

    @Test
    void addUserShouldHashPasswordWhenCalledByAdmin() throws Exception {
        User admin = userRepository.save(User.builder()
                .name("Admin")
                .email("admin@example.com")
                .password(passwordEncoder.encode("adminPassword123"))
                .address("HQ")
                .role(Role.ADMIN)
                .build());

        mockMvc.perform(post("/api/v1/users/add")
                        .header("Authorization", bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Dave",
                                  "email": "dave@example.com",
                                  "password": "password123",
                                  "address": "Chennai",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isError").value(false))
                .andExpect(jsonPath("$.data.email").value("dave@example.com"));

        User savedUser = userRepository.findByEmailIgnoreCase("dave@example.com").orElseThrow();
        assertThat(savedUser.getPassword()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", savedUser.getPassword())).isTrue();
    }

    @Test
    void addProductShouldBeForbiddenForCustomer() throws Exception {
        User customer = userRepository.save(User.builder()
                .name("Eve")
                .email("eve@example.com")
                .password(passwordEncoder.encode("password123"))
                .address("Jaipur")
                .role(Role.CUSTOMER)
                .build());

        mockMvc.perform(post("/api/v1/products/add")
                        .header("Authorization", bearerToken(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Lipstick",
                                  "brand": "GUCCI",
                                  "category": "MAKEUP",
                                  "price": 999.0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.isError").value(true))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void addProductShouldAllowAdmin() throws Exception {
        User admin = userRepository.save(User.builder()
                .name("Admin")
                .email("admin@example.com")
                .password(passwordEncoder.encode("adminPassword123"))
                .address("HQ")
                .role(Role.ADMIN)
                .build());

        mockMvc.perform(post("/api/v1/products/add")
                        .header("Authorization", bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Perfume",
                                  "brand": "PRADA",
                                  "category": "PERFUME",
                                  "price": 1999.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isError").value(false))
                .andExpect(jsonPath("$.data.name").value("Perfume"));

        assertThat(productRepository.count()).isEqualTo(1);
    }

    private String bearerToken(User user) {
        String token = jwtService.generateToken(user);
        userSessionRepository.save(UserSession.builder()
                .user(user)
                .tokenId(jwtService.extractTokenId(token))
                .accessToken(token)
                .issuedAt(jwtService.extractIssuedAt(token))
                .expiresAt(jwtService.extractExpiration(token))
                .revoked(false)
                .lastUsedAt(jwtService.extractIssuedAt(token))
                .build());
        return "Bearer " + token;
    }
}


