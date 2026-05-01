package com.avijeet.nykaa.service.order;

import com.avijeet.nykaa.dto.order.CartItemRequestDto;
import com.avijeet.nykaa.dto.order.OrderItemDto;
import com.avijeet.nykaa.dto.order.OrderResponseDto;
import com.avijeet.nykaa.entities.order.Order;
import com.avijeet.nykaa.entities.order.OrderItem;
import com.avijeet.nykaa.entities.product.Product;
import com.avijeet.nykaa.entities.user.User;
import com.avijeet.nykaa.enums.OrderState;
import com.avijeet.nykaa.exception.ProductNotFoundException;
import com.avijeet.nykaa.exception.order.EmptyCartException;
import com.avijeet.nykaa.exception.user.UserNotFoundException;
import com.avijeet.nykaa.repository.order.OrderRepository;
import com.avijeet.nykaa.repository.product.ProductRepository;
import com.avijeet.nykaa.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Transactional
    public OrderResponseDto addToCart(Long userId, CartItemRequestDto request) {
        log.info("Adding product {} to cart for user {}", request.productId(), userId);
        
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

            Product product = productRepository.findById(request.productId())
                    .orElseThrow(() -> new ProductNotFoundException("Product not found: " + request.productId()));

            Order order = orderRepository.findByUserIdAndStatus(userId, OrderState.PENDING)
                    .orElseGet(() -> {
                        Order newOrder = Order.builder()
                                .user(user)
                                .status(OrderState.PENDING)
                                .build();
                        return orderRepository.save(newOrder);
                    });

            // Check if item already exists in cart, if so update quantity, else add new
            Optional<OrderItem> existingItem = order.getItems().stream()
                    .filter(item -> item.getProduct().getId().equals(product.getId()))
                    .findFirst();

            if (existingItem.isPresent()) {
                OrderItem item = existingItem.get();
                item.setQuantity(item.getQuantity() + request.quantity());
            } else {
                OrderItem newItem = OrderItem.builder()
                        .product(product)
                        .quantity(request.quantity())
                        .priceAtPurchase(product.getPrice())
                        .build();
                order.addItem(newItem);
            }

            order.recalculateTotal();
            Order savedOrder = orderRepository.save(order);
            log.info("Successfully updated cart for user {}. Total items: {}", userId, savedOrder.getItems().size());
            
            return mapToResponseDto(savedOrder);
            
        } catch (Exception e) {
            log.error("Error adding to cart: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public OrderResponseDto placeOrder(Long userId) {
        log.info("Attempting to place order for user {}", userId);
        
        try {
            Order order = orderRepository.findByUserIdAndStatus(userId, OrderState.PENDING)
                    .orElseThrow(() -> new EmptyCartException("No pending order/cart found for user"));

            if (order.getItems().isEmpty()) {
                throw new EmptyCartException("Cannot place order with empty cart");
            }

            // In a real system, payment processing logic would go here
            // For now, we simulate a successful order placement
            try {
                // Simulate processing
                order.setStatus(OrderState.SUCCESS);
                Order savedOrder = orderRepository.save(order);
                log.info("Successfully placed order {} for user {}", savedOrder.getId(), userId);
                return mapToResponseDto(savedOrder);
                
            } catch (Exception paymentFailure) {
                log.error("Failed to process order {}: {}", order.getId(), paymentFailure.getMessage());
                order.setStatus(OrderState.FAILED);
                orderRepository.save(order);
                throw new RuntimeException("Failed to process order", paymentFailure);
            }
            
        } catch (Exception e) {
            log.error("Error placing order for user {}: {}", userId, e.getMessage(), e);
            throw e;
        }
    }

    public OrderResponseDto getCart(Long userId) {
        log.info("Fetching cart for user {}", userId);
        Order order = orderRepository.findByUserIdAndStatus(userId, OrderState.PENDING)
                .orElseThrow(() -> new EmptyCartException("No active cart found"));
        return mapToResponseDto(order);
    }

    private OrderResponseDto mapToResponseDto(Order order) {
        return OrderResponseDto.builder()
                .orderId(order.getId())
                .userId(order.getUser().getId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .items(order.getItems().stream()
                        .map(item -> OrderItemDto.builder()
                                .productId(item.getProduct().getId())
                                .productName(item.getProduct().getName())
                                .quantity(item.getQuantity())
                                .priceAtPurchase(item.getPriceAtPurchase())
                                .build())
                        .toList())
                .build();
    }
}
