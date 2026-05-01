package com.avijeet.nykaa.service.payment;

import com.avijeet.nykaa.constants.KafkaTopics;
import com.avijeet.nykaa.dto.payment.WebhookPaymentRequest;
import com.avijeet.nykaa.dto.payment.WebhookPaymentResponse;
import com.avijeet.nykaa.dto.saga.PaymentFailedEvent;
import com.avijeet.nykaa.dto.saga.PaymentProcessedEvent;
import com.avijeet.nykaa.entities.order.Order;
import com.avijeet.nykaa.enums.OrderState;
import com.avijeet.nykaa.repository.order.OrderRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final OrderRepository orderRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Tracer tracer;

    private static final String IDEMPOTENCY_PREFIX = "nykaa:idempotency:payment:webhook:";
    private static final Duration IDEMPOTENCY_TTL  = Duration.ofHours(24);

    /**
     * Processes an external payment gateway webhook.
     * Idempotency key is stored in Redis so duplicate webhook deliveries
     * return the same response without re-publishing Kafka events.
     */
    public WebhookPaymentResponse processWebhook(WebhookPaymentRequest request) {
        Span span = tracer.nextSpan().name("payment.webhook").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            span.tag("order.id", String.valueOf(request.orderId()));
            span.tag("payment.id", request.paymentId());
            span.tag("payment.status", request.status());
            return processWebhookInternal(request);
        } catch (Exception ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    private WebhookPaymentResponse processWebhookInternal(WebhookPaymentRequest request) {
        String idempotencyKey = IDEMPOTENCY_PREFIX + request.idempotencyKey();
        log.info("[PAY-WEBHOOK] Received webhook for order {} paymentId={} status={}",
                request.orderId(), request.paymentId(), request.status());

        // Return cached result for duplicate delivery
        String cached = (String) redisTemplate.opsForValue().get(idempotencyKey);
        if (cached != null) {
            log.warn("[PAY-WEBHOOK] Duplicate webhook for idempotencyKey={}. Returning cached response.", request.idempotencyKey());
            return WebhookPaymentResponse.builder()
                    .orderId(request.orderId())
                    .paymentId(request.paymentId())
                    .status(request.status())
                    .processingStatus("DUPLICATE")
                    .build();
        }

        Order order = orderRepository.findById(request.orderId()).orElse(null);
        if (order == null) {
            log.error("[PAY-WEBHOOK] Order {} not found.", request.orderId());
            return WebhookPaymentResponse.builder()
                    .orderId(request.orderId())
                    .paymentId(request.paymentId())
                    .status(request.status())
                    .processingStatus("ORDER_NOT_FOUND")
                    .build();
        }

        if (order.getStatus() != OrderState.PENDING) {
            log.warn("[PAY-WEBHOOK] Order {} not PENDING (state={}). Ignoring webhook.",
                    request.orderId(), order.getStatus());
            redisTemplate.opsForValue().set(idempotencyKey, request.paymentId(), IDEMPOTENCY_TTL);
            return WebhookPaymentResponse.builder()
                    .orderId(request.orderId())
                    .paymentId(request.paymentId())
                    .status(request.status())
                    .processingStatus("SKIPPED_NON_PENDING")
                    .build();
        }

        // Persist idempotency key before publishing to Kafka —
        // ensures at-most-once event publication even if the process crashes after publish.
        redisTemplate.opsForValue().set(idempotencyKey, request.paymentId(), IDEMPOTENCY_TTL);

        if ("SUCCESS".equalsIgnoreCase(request.status())) {
            kafkaTemplate.send(
                    KafkaTopics.PAYMENT_PROCESSED,
                    String.valueOf(request.orderId()),
                    PaymentProcessedEvent.builder()
                            .orderId(request.orderId())
                            .userId(request.userId())
                            .paymentId(request.paymentId())
                            .totalAmount(request.totalAmount())
                            .build());
            log.info("[PAY-WEBHOOK] PaymentProcessed published for order {}.", request.orderId());

            return WebhookPaymentResponse.builder()
                    .orderId(request.orderId())
                    .paymentId(request.paymentId())
                    .status(request.status())
                    .processingStatus("ACCEPTED")
                    .build();
        } else {
            kafkaTemplate.send(
                    KafkaTopics.PAYMENT_FAILED,
                    String.valueOf(request.orderId()),
                    PaymentFailedEvent.builder()
                            .orderId(request.orderId())
                            .userId(request.userId())
                            .reason(request.reason() != null ? request.reason() : "Payment gateway returned: " + request.status())
                            .build());
            log.info("[PAY-WEBHOOK] PaymentFailed published for order {}.", request.orderId());

            return WebhookPaymentResponse.builder()
                    .orderId(request.orderId())
                    .paymentId(request.paymentId())
                    .status(request.status())
                    .processingStatus("ACCEPTED")
                    .build();
        }
    }
}
