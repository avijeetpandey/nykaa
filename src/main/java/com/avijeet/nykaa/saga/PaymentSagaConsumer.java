package com.avijeet.nykaa.saga;

import com.avijeet.nykaa.constants.KafkaTopics;
import com.avijeet.nykaa.dto.saga.InventoryReservedEvent;
import com.avijeet.nykaa.dto.saga.PaymentFailedEvent;
import com.avijeet.nykaa.dto.saga.PaymentProcessedEvent;
import com.avijeet.nykaa.repository.order.OrderRepository;
import com.avijeet.nykaa.enums.OrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentSagaConsumer {

    private final OrderRepository orderRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String IDEMPOTENCY_PREFIX = "nykaa:idempotency:payment:order:";
    private static final Duration IDEMPOTENCY_TTL  = Duration.ofHours(24);

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_RESERVED,
            groupId = "nykaa-payment-saga",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleInventoryReserved(InventoryReservedEvent event, Acknowledgment ack) {
        Long orderId = event.getOrderId();
        log.info("[SAGA-PAY] InventoryReserved received for order {}", orderId);

        // Idempotency guard — if order is no longer PENDING, skip
        var order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderState.PENDING) {
            log.warn("[SAGA-PAY] Order {} not PENDING (state={}). Skipping.",
                    orderId, order != null ? order.getStatus() : "NOT_FOUND");
            ack.acknowledge();
            return;
        }

        String idempotencyKey = IDEMPOTENCY_PREFIX + orderId;

        // Check whether payment was already attempted for this order
        String existingPaymentId = (String) redisTemplate.opsForValue().get(idempotencyKey);
        if (existingPaymentId != null) {
            log.warn("[SAGA-PAY] Payment already processed for order {} (paymentId={}). Skipping duplicate.",
                    orderId, existingPaymentId);
            ack.acknowledge();
            return;
        }

        String paymentId = "PAY-" + UUID.randomUUID();

        try {
            // Mock payment — always succeeds in this internal flow.
            // Real payment gateway integration lives in PaymentService (webhook path).
            redisTemplate.opsForValue().set(idempotencyKey, paymentId, IDEMPOTENCY_TTL);

            kafkaTemplate.send(
                    KafkaTopics.PAYMENT_PROCESSED,
                    String.valueOf(orderId),
                    PaymentProcessedEvent.builder()
                            .orderId(orderId)
                            .userId(event.getUserId())
                            .paymentId(paymentId)
                            .totalAmount(event.getTotalAmount())
                            .build());

            log.info("[SAGA-PAY] Payment processed for order {}. paymentId={}. Publishing PaymentProcessed.",
                    orderId, paymentId);

        } catch (Exception ex) {
            log.error("[SAGA-PAY] Payment failed for order {}: {}", orderId, ex.getMessage());

            kafkaTemplate.send(
                    KafkaTopics.PAYMENT_FAILED,
                    String.valueOf(orderId),
                    PaymentFailedEvent.builder()
                            .orderId(orderId)
                            .userId(event.getUserId())
                            .reason("Payment processing failed: " + ex.getMessage())
                            .build());

            log.info("[SAGA-PAY] PaymentFailed published for order {}.", orderId);
        }

        ack.acknowledge();
    }
}
