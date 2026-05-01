package com.avijeet.nykaa.saga;

import com.avijeet.nykaa.constants.KafkaTopics;
import com.avijeet.nykaa.dto.saga.InventoryReservedEvent;
import com.avijeet.nykaa.dto.saga.OrderCancelledEvent;
import com.avijeet.nykaa.dto.saga.OrderCreatedEvent;
import com.avijeet.nykaa.dto.saga.OrderLineItem;
import com.avijeet.nykaa.entities.order.Order;
import com.avijeet.nykaa.enums.OrderState;
import com.avijeet.nykaa.repository.order.OrderRepository;
import com.avijeet.nykaa.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventorySagaConsumer {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(
            topics = KafkaTopics.ORDER_CREATED,
            groupId = "nykaa-inventory-saga",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(OrderCreatedEvent event, Acknowledgment ack) {
        Long orderId = event.getOrderId();
        log.info("[SAGA-INV] OrderCreated received for order {}", orderId);

        // Idempotency guard — if the order moved out of PENDING already, skip
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderState.PENDING) {
            log.warn("[SAGA-INV] Order {} is not PENDING (state={}). Skipping.",
                    orderId, order != null ? order.getStatus() : "NOT_FOUND");
            ack.acknowledge();
            return;
        }

        List<OrderLineItem> reserved = new ArrayList<>();
        try {
            for (OrderLineItem item : event.getItems()) {
                inventoryService.reserveStock(item.getProductId(), item.getQuantity());
                reserved.add(item);
            }

            kafkaTemplate.send(
                    KafkaTopics.INVENTORY_RESERVED,
                    String.valueOf(orderId),
                    InventoryReservedEvent.builder()
                            .orderId(orderId)
                            .userId(event.getUserId())
                            .items(event.getItems())
                            .totalAmount(event.getTotalAmount())
                            .build());

            log.info("[SAGA-INV] Inventory reserved for order {}. Publishing InventoryReserved.", orderId);

        } catch (Exception ex) {
            log.error("[SAGA-INV] Inventory reservation failed for order {}: {}", orderId, ex.getMessage());

            for (OrderLineItem item : reserved) {
                try {
                    inventoryService.rollbackStock(item.getProductId(), item.getQuantity());
                } catch (Exception rollbackEx) {
                    log.error("[SAGA-INV] Rollback failed for product {} in order {}: {}",
                            item.getProductId(), orderId, rollbackEx.getMessage());
                }
            }

            kafkaTemplate.send(
                    KafkaTopics.ORDER_CANCELLED,
                    String.valueOf(orderId),
                    OrderCancelledEvent.builder()
                            .orderId(orderId)
                            .userId(event.getUserId())
                            .reason("Inventory reservation failed: " + ex.getMessage())
                            .build());

            log.info("[SAGA-INV] Compensated. OrderCancelled published for order {}.", orderId);
        }

        ack.acknowledge();
    }
}
