package com.avijeet.nykaa.dto.payment;

import lombok.Builder;

@Builder
public record WebhookPaymentResponse(
        Long orderId,
        String paymentId,
        String status,
        String processingStatus
) {}
