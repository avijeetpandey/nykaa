package com.avijeet.nykaa.dto.auth;

import com.avijeet.nykaa.dto.user.UserResponseDto;
import lombok.Builder;

import java.time.Instant;

@Builder
public record AuthResponseDto(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UserResponseDto user
) {}

