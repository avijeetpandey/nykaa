package com.avijeet.nykaa.service.user;

import com.avijeet.nykaa.dto.user.UserRequestDto;
import com.avijeet.nykaa.dto.user.UserResponseDto;
import com.avijeet.nykaa.dto.user.UserUpdateRequestDto;
import com.avijeet.nykaa.entities.user.User;
import com.avijeet.nykaa.enums.Role;
import com.avijeet.nykaa.exception.user.UserNotFoundException;
import com.avijeet.nykaa.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;

    @Transactional
    public UserResponseDto addUser(UserRequestDto userRequestDto) {
        log.info("Attempting to add user with email: {}", userRequestDto.email());
        try {
            User user = toEntity(userRequestDto);
            User savedUser = userRepository.save(user);
            log.info("User added successfully: id={}, name={}, email={}", savedUser.getId(), userRequestDto.name(), userRequestDto.email());
            return toResponseDto(savedUser);
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
            userRepository.delete(user);
            log.info("User deleted successfully: id={}", id);
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
            updatedUser.setName(userUpdateRequestDto.name());
            updatedUser.setEmail(userUpdateRequestDto.email());
            updatedUser.setAddress(userUpdateRequestDto.address());

            User savedUser = userRepository.save(updatedUser);

            log.info("User updated successfully: id={}, name={}, email={}", savedUser.getId(), userUpdateRequestDto.name(), userUpdateRequestDto.email());
            return toResponseDto(savedUser);
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
                .email(userRequestDto.email())
                .address(userRequestDto.address())
                .password(userRequestDto.password())
                .role(Role.valueOf(userRequestDto.role()))
                .build();
    }
}