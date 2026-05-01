package com.avijeet.nykaa.controller.order;

import com.avijeet.nykaa.constants.ApiConstants;
import com.avijeet.nykaa.dto.order.CartItemRequestDto;
import com.avijeet.nykaa.dto.order.OrderResponseDto;
import com.avijeet.nykaa.service.order.OrderService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final com.avijeet.nykaa.repository.user.UserRepository userRepository; // Need to resolve userId from email

    private Long getUserIdFromEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new RuntimeException("User not found for email: " + email))
                .getId();
    }

    @PostMapping("/cart/add")
    public ResponseEntity<ApiResponse<OrderResponseDto>> addToCart(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CartItemRequestDto request) {
        
        Long userId = getUserIdFromEmail(email);
        log.info("POST /api/v1/orders/cart/add called for user: {}", userId);
        
        OrderResponseDto response = orderService.addToCart(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Item added to cart", response));
    }

    @GetMapping("/cart")
    public ResponseEntity<ApiResponse<OrderResponseDto>> getCart(
            @AuthenticationPrincipal String email) {
        
        Long userId = getUserIdFromEmail(email);
        log.info("GET /api/v1/orders/cart called for user: {}", userId);
        
        OrderResponseDto response = orderService.getCart(userId);
        return ResponseEntity.ok(ApiResponse.success("Cart fetched successfully", response));
    }

    @PostMapping("/place")
    public ResponseEntity<ApiResponse<OrderResponseDto>> placeOrder(
            @AuthenticationPrincipal String email) {
        
        Long userId = getUserIdFromEmail(email);
        log.info("POST /api/v1/orders/place called for user: {}", userId);
        
        OrderResponseDto response = orderService.placeOrder(userId);
        return ResponseEntity.ok(ApiResponse.success("Order placed successfully", response));
    }
}
