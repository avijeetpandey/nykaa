package com.avijeet.nykaa.controller.auth;

import com.avijeet.nykaa.dto.auth.AuthLoginRequestDto;
import com.avijeet.nykaa.dto.auth.AuthRegisterRequestDto;
import com.avijeet.nykaa.dto.auth.AuthResponseDto;
import com.avijeet.nykaa.dto.user.UserResponseDto;
import com.avijeet.nykaa.service.auth.AuthService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponseDto>> register(@Valid @RequestBody AuthRegisterRequestDto requestDto) {
        log.info("POST /api/v1/auth/register called for email: {}", requestDto.email());
        AuthResponseDto response = authService.register(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User registered successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponseDto>> login(@Valid @RequestBody AuthLoginRequestDto requestDto) {
        log.info("POST /api/v1/auth/login called for email: {}", requestDto.email());
        AuthResponseDto response = authService.login(requestDto);
        return ResponseEntity.ok(ApiResponse.success("User logged in successfully", response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponseDto>> me(Authentication authentication) {
        log.info("GET /api/v1/auth/me called for principal: {}", authentication.getName());
        UserResponseDto response = authService.getAuthenticatedUser(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success("Authenticated user fetched successfully", response));
    }
}

