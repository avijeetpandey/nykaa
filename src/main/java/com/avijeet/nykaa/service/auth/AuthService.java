package com.avijeet.nykaa.service.auth;

import com.avijeet.nykaa.dto.auth.AuthLoginRequestDto;
import com.avijeet.nykaa.dto.auth.AuthRegisterRequestDto;
import com.avijeet.nykaa.dto.auth.AuthResponseDto;
import com.avijeet.nykaa.dto.user.UserResponseDto;
import com.avijeet.nykaa.entities.auth.UserSession;
import com.avijeet.nykaa.entities.user.User;
import com.avijeet.nykaa.enums.Role;
import com.avijeet.nykaa.exception.auth.InvalidCredentialsException;
import com.avijeet.nykaa.exception.auth.UserAlreadyExistsException;
import com.avijeet.nykaa.exception.user.UserNotFoundException;
import com.avijeet.nykaa.repository.auth.UserSessionRepository;
import com.avijeet.nykaa.repository.user.UserRepository;
import com.avijeet.nykaa.utils.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponseDto register(AuthRegisterRequestDto requestDto) {
        log.info("Registering user with email: {}", requestDto.email());

        if (userRepository.existsByEmailIgnoreCase(requestDto.email())) {
            throw new UserAlreadyExistsException("User already exists with email: " + requestDto.email());
        }

        User user = User.builder()
                .name(requestDto.name())
                .email(requestDto.email().trim().toLowerCase())
                .password(passwordEncoder.encode(requestDto.password()))
                .address(requestDto.address())
                .role(Role.CUSTOMER)
                .build();

        User savedUser = userRepository.save(user);
        log.info("User registered successfully with id: {}", savedUser.getId());
        return buildAuthResponse(savedUser);
    }

    @Transactional
    public AuthResponseDto login(AuthLoginRequestDto requestDto) {
        log.info("Authenticating user with email: {}", requestDto.email());

        User user = userRepository.findByEmailIgnoreCase(requestDto.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(requestDto.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponseDto getAuthenticatedUser(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UserNotFoundException("Authenticated user not found"));
        return toUserResponse(user);
    }

    private AuthResponseDto buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        persistSession(user, token);
        return AuthResponseDto.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresAt(jwtService.extractExpiration(token))
                .user(toUserResponse(user))
                .build();
    }

    private void persistSession(User user, String token) {
        UserSession userSession = UserSession.builder()
                .user(user)
                .tokenId(jwtService.extractTokenId(token))
                .accessToken(token)
                .issuedAt(jwtService.extractIssuedAt(token))
                .expiresAt(jwtService.extractExpiration(token))
                .revoked(false)
                .lastUsedAt(jwtService.extractIssuedAt(token))
                .build();
        userSessionRepository.save(userSession);
    }

    private UserResponseDto toUserResponse(User user) {
        return UserResponseDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .address(user.getAddress())
                .role(user.getRole().name())
                .build();
    }
}

