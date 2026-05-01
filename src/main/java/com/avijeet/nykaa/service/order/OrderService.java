package com.avijeet.nykaa.service.order;

import com.avijeet.nykaa.constants.KafkaTopics;
import com.avijeet.nykaa.dto.cart.CartItemData;
import com.avijeet.nykaa.dto.order.OrderItemDto;
import com.avijeet.nykaa.dto.order.OrderResponseDto;
import com.avijeet.nykaa.dto.saga.OrderCreatedEvent;
import com.avijeet.nykaa.dto.saga.OrderLineItem;
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
import com.avijeet.nykaa.service.cart.CartService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CartService cartService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Tracer tracer;

    /**
     * Creates an Order(PENDING) from the Redis cart, then fires an OrderCreated event
     * to kick off the Kafka Saga choreography:
     *
     *   OrderCreated → InventoryReserved → PaymentProcessed → Order(SUCCESS)
     *                                    ↘ PaymentFailed    → Order(FAILED)
     *              ↘ OrderCancelled                         → Order(FAILED)
     *
     * Returns immediately with the PENDING order; callers poll GET /orders/{id} for resolution.
     */
    @Transactional
    public OrderResponseDto placeOrder(Long userId) {
        Span span = tracer.nextSpan().name("order.place").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            span.tag("user.id", String.valueOf(userId));
            log.info("[ORDER] PlaceOrder initiated for user {}", userId);

            if (cartService.isCartEmpty(userId)) {
                throw new EmptyCartException("Cart is empty for user: " + userId);
            }

            List<CartItemData> cartItems = cartService.getCartItems(userId);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

            Order order = Order.builder().user(user).status(OrderState.PENDING).build();

            for (CartItemData item : cartItems) {
                Product product = productRepository.findById(item.getProductId())
                        .orElseThrow(() -> new ProductNotFoundException("Product not found: " + item.getProductId()));
                order.addItem(OrderItem.builder()
                        .product(product)
                        .quantity(item.getQuantity())
                        .priceAtPurchase(item.getPrice())
                        .build());
            }

            Order saved = orderRepository.save(order);
            cartService.clearCart(userId);

            span.tag("order.id", String.valueOf(saved.getId()));
            span.tag("order.total", String.valueOf(saved.getTotalAmount()));
            span.tag("order.items", String.valueOf(saved.getItems().size()));

            List<OrderLineItem> lineItems = saved.getItems().stream()
                    .map(oi -> OrderLineItem.builder()
                            .productId(oi.getProduct().getId())
                            .quantity(oi.getQuantity())
                            .priceAtPurchase(oi.getPriceAtPurchase())
                            .build())
                    .toList();

            kafkaTemplate.send(
                    KafkaTopics.ORDER_CREATED,
                    String.valueOf(saved.getId()),
                    OrderCreatedEvent.builder()
                            .orderId(saved.getId())
                            .userId(userId)
                            .items(lineItems)
                            .totalAmount(saved.getTotalAmount())
                            .build());

            log.info("[ORDER] Order {} persisted as PENDING. Saga triggered via {}", saved.getId(), KafkaTopics.ORDER_CREATED);
            return mapToResponseDto(saved);
        } catch (Exception ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    @Transactional(readOnly = true)
    public OrderResponseDto getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        return mapToResponseDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> getOrderHistory(Long userId) {
        return orderRepository.findAllByUserId(userId).stream()
                .map(this::mapToResponseDto)
                .toList();
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
