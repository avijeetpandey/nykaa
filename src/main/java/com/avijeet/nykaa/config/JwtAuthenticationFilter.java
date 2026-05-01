package com.avijeet.nykaa.config;

import com.avijeet.nykaa.entities.auth.UserSession;
import com.avijeet.nykaa.entities.user.User;
import com.avijeet.nykaa.repository.auth.UserSessionRepository;
import com.avijeet.nykaa.repository.user.UserRepository;
import com.avijeet.nykaa.utils.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());

        try {
            String email = jwtService.extractEmail(token);
            String tokenId = jwtService.extractTokenId(token);
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                userRepository.findByEmailIgnoreCase(email)
                        .flatMap(user -> findActiveSession(user, tokenId, token)
                                .filter(session -> jwtService.isTokenValid(token, user))
                                .map(session -> {
                                    session.setLastUsedAt(Instant.now());
                                    userSessionRepository.save(session);
                                    return user;
                                }))
                        .ifPresent(user -> setAuthentication(user, request));
            }
        } catch (JwtService.InvalidJwtTokenException ex) {
            log.warn("JWT authentication failed for request {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private java.util.Optional<UserSession> findActiveSession(User user, String tokenId, String token) {
        return userSessionRepository.findByTokenIdAndRevokedFalse(tokenId)
                .filter(session -> session.getUser().getId().equals(user.getId()))
                .filter(session -> session.getExpiresAt().isAfter(Instant.now()))
                .filter(session -> session.getAccessToken().equals(token));
    }

    private void setAuthentication(User user, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

