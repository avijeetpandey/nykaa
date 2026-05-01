package com.avijeet.nykaa.service.cart;

import com.avijeet.nykaa.dto.cart.CartItemData;
import com.avijeet.nykaa.dto.cart.CartResponseDto;
import com.avijeet.nykaa.entities.product.Product;
import com.avijeet.nykaa.exception.ProductNotFoundException;
import com.avijeet.nykaa.exception.cart.CartNotFoundException;
import com.avijeet.nykaa.repository.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductRepository productRepository;

    private static final String CART_KEY_PREFIX = "nykaa:cart:";
    private static final Duration CART_TTL = Duration.ofMinutes(30);

    public CartResponseDto addItem(Long userId, Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));

        String cartKey = cartKey(userId);
        Object existing = redisTemplate.opsForHash().get(cartKey, String.valueOf(productId));

        CartItemData item;
        if (existing instanceof CartItemData existingItem) {
            item = CartItemData.builder()
                    .productId(productId)
                    .productName(existingItem.getProductName())
                    .price(existingItem.getPrice())
                    .quantity(existingItem.getQuantity() + quantity)
                    .build();
        } else {
            item = CartItemData.builder()
                    .productId(productId)
                    .productName(product.getName())
                    .price(product.getPrice())
                    .quantity(quantity)
                    .build();
        }

        redisTemplate.opsForHash().put(cartKey, String.valueOf(productId), item);
        redisTemplate.expire(cartKey, CART_TTL);
        log.info("Cart updated for user {}: product {} qty={}", userId, productId, item.getQuantity());
        return buildResponse(userId, cartKey);
    }

    public CartResponseDto getCart(Long userId) {
        String cartKey = cartKey(userId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(cartKey))) {
            throw new CartNotFoundException("No active cart found for user: " + userId);
        }
        return buildResponse(userId, cartKey);
    }

    public void removeItem(Long userId, Long productId) {
        redisTemplate.opsForHash().delete(cartKey(userId), String.valueOf(productId));
        redisTemplate.expire(cartKey(userId), CART_TTL);
        log.info("Removed product {} from cart of user {}", productId, userId);
    }

    public void clearCart(Long userId) {
        redisTemplate.delete(cartKey(userId));
        log.info("Cart cleared for user {}", userId);
    }

    public boolean isCartEmpty(Long userId) {
        String cartKey = cartKey(userId);
        return !Boolean.TRUE.equals(redisTemplate.hasKey(cartKey))
                || redisTemplate.opsForHash().size(cartKey) == 0;
    }

    public List<CartItemData> getCartItems(Long userId) {
        return redisTemplate.opsForHash().values(cartKey(userId)).stream()
                .filter(CartItemData.class::isInstance)
                .map(CartItemData.class::cast)
                .toList();
    }

    private CartResponseDto buildResponse(Long userId, String cartKey) {
        List<CartItemData> items = redisTemplate.opsForHash().values(cartKey).stream()
                .filter(CartItemData.class::isInstance)
                .map(CartItemData.class::cast)
                .toList();
        double total = items.stream().mapToDouble(i -> i.getPrice() * i.getQuantity()).sum();
        return CartResponseDto.builder().userId(userId).items(items).totalAmount(total).build();
    }

    private String cartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }
}
