package com.avijeet.nykaa.dto.user;

import lombok.Builder;

@Builder
public record UserResponseDto(
        Long id,
        String name,
        String email,
        String address,
        String role
) {}
