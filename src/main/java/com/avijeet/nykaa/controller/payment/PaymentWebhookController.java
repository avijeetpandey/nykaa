package com.avijeet.nykaa.controller.payment;

import com.avijeet.nykaa.annotation.RateLimit;
import com.avijeet.nykaa.dto.payment.WebhookPaymentRequest;
import com.avijeet.nykaa.dto.payment.WebhookPaymentResponse;
import com.avijeet.nykaa.service.payment.PaymentService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentService paymentService;

    @PostMapping("/webhook")
    @RateLimit(capacity = 20, refillPerMinute = 20)
    public ResponseEntity<ApiResponse<WebhookPaymentResponse>> handleWebhook(
            @Valid @RequestBody WebhookPaymentRequest request) {
        log.info("POST /payments/webhook  orderId={} status={}", request.orderId(), request.status());
        WebhookPaymentResponse response = paymentService.processWebhook(request);
        return ResponseEntity.accepted().body(ApiResponse.success("Webhook processed", response));
    }
}
