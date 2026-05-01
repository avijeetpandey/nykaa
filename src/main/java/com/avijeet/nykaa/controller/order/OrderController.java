package com.avijeet.nykaa.controller.order;

import com.avijeet.nykaa.annotation.RateLimit;
import com.avijeet.nykaa.dto.cart.CartItemRequestDto;
import com.avijeet.nykaa.dto.cart.CartResponseDto;
import com.avijeet.nykaa.dto.order.OrderResponseDto;
import com.avijeet.nykaa.repository.user.UserRepository;
import com.avijeet.nykaa.service.cart.CartService;
import com.avijeet.nykaa.service.order.OrderService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final CartService cartService;
    private final OrderService orderService;
    private final UserRepository userRepository;

    @PostMapping("/cart/add")
    public ResponseEntity<ApiResponse<CartResponseDto>> addToCart(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CartItemRequestDto request) {
        Long userId = resolveUserId(email);
        log.info("POST /cart/add  user={} product={} qty={}", userId, request.productId(), request.quantity());
        CartResponseDto cart = cartService.addItem(userId, request.productId(), request.quantity());
        return ResponseEntity.ok(ApiResponse.success("Item added to cart", cart));
    }

    @GetMapping("/cart")
    public ResponseEntity<ApiResponse<CartResponseDto>> getCart(
            @AuthenticationPrincipal String email) {
        Long userId = resolveUserId(email);
        log.info("GET /cart  user={}", userId);
        return ResponseEntity.ok(ApiResponse.success("Cart fetched successfully", cartService.getCart(userId)));
    }

    @DeleteMapping("/cart/remove/{productId}")
    public ResponseEntity<ApiResponse<Void>> removeFromCart(
            @AuthenticationPrincipal String email,
            @PathVariable Long productId) {
        Long userId = resolveUserId(email);
        log.info("DELETE /cart/remove/{}  user={}", productId, userId);
        cartService.removeItem(userId, productId);
        return ResponseEntity.ok(ApiResponse.success("Item removed from cart", null));
    }

    @PostMapping("/place")
    @RateLimit(capacity = 10, refillPerMinute = 10)
    public ResponseEntity<ApiResponse<OrderResponseDto>> placeOrder(
            @AuthenticationPrincipal String email) {
        Long userId = resolveUserId(email);
        log.info("POST /place  user={}", userId);
        return ResponseEntity.accepted()
                .body(ApiResponse.success("Order accepted — saga started. Poll GET /orders/{id} for status.", orderService.placeOrder(userId)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponseDto>> getOrder(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.success("Order fetched", orderService.getOrder(orderId)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<OrderResponseDto>>> getOrderHistory(
            @AuthenticationPrincipal String email) {
        Long userId = resolveUserId(email);
        return ResponseEntity.ok(ApiResponse.success("Order history fetched", orderService.getOrderHistory(userId)));
    }

    private Long resolveUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new RuntimeException("User not found for email: " + email))
                .getId();
    }
}
