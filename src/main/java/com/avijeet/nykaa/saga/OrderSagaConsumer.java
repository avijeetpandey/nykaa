package com.avijeet.nykaa.saga;

import com.avijeet.nykaa.constants.KafkaTopics;
import com.avijeet.nykaa.dto.saga.OrderCancelledEvent;
import com.avijeet.nykaa.dto.saga.PaymentFailedEvent;
import com.avijeet.nykaa.dto.saga.PaymentProcessedEvent;
import com.avijeet.nykaa.entities.order.Order;
import com.avijeet.nykaa.enums.OrderState;
import com.avijeet.nykaa.repository.order.OrderRepository;
import com.avijeet.nykaa.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaConsumer {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_PROCESSED,
            groupId = "nykaa-order-saga",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handlePaymentProcessed(PaymentProcessedEvent event, Acknowledgment ack) {
        Long orderId = event.getOrderId();
        log.info("[SAGA-ORD] PaymentProcessed received for order {}", orderId);

        Order order = orderRepository.findByIdWithLock(orderId).orElse(null);
        if (order == null) {
            log.error("[SAGA-ORD] Order {} not found — cannot mark SUCCESS.", orderId);
            ack.acknowledge();
            return;
        }
        if (order.getStatus() != OrderState.PENDING) {
            log.warn("[SAGA-ORD] Order {} already in state {}. Skipping SUCCESS transition.",
                    orderId, order.getStatus());
            ack.acknowledge();
            return;
        }

        order.setStatus(OrderState.SUCCESS);
        orderRepository.save(order);
        log.info("[SAGA-ORD] Order {} marked SUCCESS.", orderId);

        ack.acknowledge();
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "nykaa-order-saga",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event, Acknowledgment ack) {
        Long orderId = event.getOrderId();
        log.info("[SAGA-ORD] PaymentFailed received for order {}. Reason: {}", orderId, event.getReason());

        Order order = orderRepository.findByIdWithLock(orderId).orElse(null);
        if (order == null) {
            log.error("[SAGA-ORD] Order {} not found — cannot mark FAILED.", orderId);
            ack.acknowledge();
            return;
        }
        if (order.getStatus() != OrderState.PENDING) {
            log.warn("[SAGA-ORD] Order {} already in state {}. Skipping FAILED transition.",
                    orderId, order.getStatus());
            ack.acknowledge();
            return;
        }

        // Compensate: roll back inventory for every line item
        // @Transactional keeps the JPA session open so lazy-loaded items are accessible
        order.getItems().forEach(item -> {
            try {
                inventoryService.rollbackStock(item.getProduct().getId(), item.getQuantity());
            } catch (Exception ex) {
                log.error("[SAGA-ORD] Inventory rollback failed for product {} in order {}: {}",
                        item.getProduct().getId(), orderId, ex.getMessage());
            }
        });

        order.setStatus(OrderState.FAILED);
        orderRepository.save(order);
        log.info("[SAGA-ORD] Order {} marked FAILED. Inventory compensated.", orderId);

        ack.acknowledge();
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_CANCELLED,
            groupId = "nykaa-order-saga",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleOrderCancelled(OrderCancelledEvent event, Acknowledgment ack) {
        Long orderId = event.getOrderId();
        log.info("[SAGA-ORD] OrderCancelled received for order {}. Reason: {}", orderId, event.getReason());

        Order order = orderRepository.findByIdWithLock(orderId).orElse(null);
        if (order == null) {
            log.error("[SAGA-ORD] Order {} not found — cannot mark FAILED.", orderId);
            ack.acknowledge();
            return;
        }
        if (order.getStatus() != OrderState.PENDING) {
            log.warn("[SAGA-ORD] Order {} already in state {}. Skipping FAILED transition.",
                    orderId, order.getStatus());
            ack.acknowledge();
            return;
        }

        order.setStatus(OrderState.FAILED);
        orderRepository.save(order);
        log.info("[SAGA-ORD] Order {} marked FAILED (cancelled by inventory saga).", orderId);

        ack.acknowledge();
    }
}
