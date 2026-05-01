package com.avijeet.nykaa.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRegisterRequestDto(
        @NotBlank(message = "Name cannot be empty")
        String name,

        @Email(message = "Invalid email format")
        @NotBlank(message = "Email cannot be empty")
        String email,

        @NotBlank(message = "Password cannot be empty")
        @Size(min = 8, message = "Password must be at least 8 characters long")
        String password,

        @NotBlank(message = "Address cannot be empty")
        String address
) {}

