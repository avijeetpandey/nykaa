package com.avijeet.nykaa.dto.order;

import com.avijeet.nykaa.enums.OrderState;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record OrderResponseDto(
        Long orderId,
        Long userId,
        Double totalAmount,
        OrderState status,
        List<OrderItemDto> items,
        LocalDateTime createdAt
) {}
