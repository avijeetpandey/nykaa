package com.avijeet.nykaa.service.user;

import com.avijeet.nykaa.dto.user.UserRequestDto;
import com.avijeet.nykaa.dto.user.UserResponseDto;
import com.avijeet.nykaa.dto.user.UserUpdateRequestDto;
import com.avijeet.nykaa.entities.user.User;
import com.avijeet.nykaa.enums.Role;
import com.avijeet.nykaa.exception.auth.UserAlreadyExistsException;
import com.avijeet.nykaa.exception.user.UserNotFoundException;
import com.avijeet.nykaa.repository.auth.UserSessionRepository;
import com.avijeet.nykaa.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponseDto addUser(UserRequestDto userRequestDto) {
        log.info("Attempting to add user with email: {}", userRequestDto.email());
        try {
            String normalizedEmail = normalizeEmail(userRequestDto.email());
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                throw new UserAlreadyExistsException("User already exists with email: " + normalizedEmail);
            }

            User user = toEntity(userRequestDto);
            User savedUser = userRepository.save(user);
            log.info("User added successfully: id={}, name={}, email={}", savedUser.getId(), userRequestDto.name(), userRequestDto.email());
            return toResponseDto(savedUser);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error occurred while adding user: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add user", e);
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        log.info("Attempting to delete user with id: {}", id);
        User user = userRepository.findById(id).orElseThrow(() -> {
            log.error("User not found unable to delete. Id: {}", id);
            return new UserNotFoundException("User not found unable to delete");
        });

        try {
            userSessionRepository.deleteByUserId(id);
            userRepository.delete(user);
            log.info("User deleted successfully: id={}", id);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error occurred while deleting user id {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to delete user", e);
        }
    }

    @Transactional
    public UserResponseDto updateUser(UserUpdateRequestDto userUpdateRequestDto) {
        log.info("Attempting to update user with id: {}", userUpdateRequestDto.id());
        User updatedUser = userRepository.findById(userUpdateRequestDto.id())
                .orElseThrow(() -> {
                    log.error("User not found unable to update. Id: {}", userUpdateRequestDto.id());
                    return new UserNotFoundException("User not found unable to update");
                });

        try {
            String normalizedEmail = normalizeEmail(userUpdateRequestDto.email());
            userRepository.findByEmailIgnoreCase(normalizedEmail)
                    .filter(existingUser -> !existingUser.getId().equals(updatedUser.getId()))
                    .ifPresent(existingUser -> {
                        throw new UserAlreadyExistsException("User already exists with email: " + normalizedEmail);
                    });

            updatedUser.setName(userUpdateRequestDto.name());
            updatedUser.setEmail(normalizedEmail);
            updatedUser.setAddress(userUpdateRequestDto.address());

            User savedUser = userRepository.save(updatedUser);

            log.info("User updated successfully: id={}, name={}, email={}", savedUser.getId(), userUpdateRequestDto.name(), userUpdateRequestDto.email());
            return toResponseDto(savedUser);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error occurred while updating user id {}: {}", userUpdateRequestDto.id(), e.getMessage(), e);
            throw new RuntimeException("Failed to update user", e);
        }
    }

    private UserResponseDto toResponseDto(User user) {
        return UserResponseDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .address(user.getAddress())
                .role(user.getRole().name())
                .build();
    }

    private User toEntity(UserRequestDto userRequestDto) {
        return User.builder()
                .name(userRequestDto.name())
                .email(normalizeEmail(userRequestDto.email()))
                .address(userRequestDto.address())
                .password(passwordEncoder.encode(userRequestDto.password()))
                .role(resolveRole(userRequestDto.role()))
                .build();
    }

    private Role resolveRole(String role) {
        if (role == null || role.isBlank()) {
            return Role.CUSTOMER;
        }
        return Role.valueOf(role.trim().toUpperCase());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}