package com.avijeet.nykaa.repository.auth;

import com.avijeet.nykaa.entities.auth.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findByTokenId(String tokenId);

    Optional<UserSession> findByTokenIdAndRevokedFalse(String tokenId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}


