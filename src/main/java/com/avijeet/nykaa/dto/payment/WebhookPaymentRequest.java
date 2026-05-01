package com.avijeet.nykaa.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record WebhookPaymentRequest(
        @NotNull(message = "orderId is required")
        Long orderId,

        @NotNull(message = "userId is required")
        Long userId,

        @NotBlank(message = "paymentId is required")
        String paymentId,

        @NotBlank(message = "status is required — SUCCESS or FAILURE")
        String status,

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey,

        Double totalAmount,
        String reason
) {}
