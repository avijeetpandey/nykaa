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

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;

    @Transactional
    public UserResponseDto addUser(UserRequestDto userRequestDto) {
        User user = toEntity(userRequestDto);
        userRepository.save(user);
        log.info("User added successfully: name={}, email={}", userRequestDto.name(), userRequestDto.email());
        return toResponseDto(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        Optional<User> user = userRepository.findById(id);
        if(user.isEmpty()) {
            log.error("User not found unable to delete");
            throw new UserNotFoundException("User not found unable to delete");
        }
        userRepository.delete(user.get());
        log.info("User deleted successfully");
    }

    @Transactional
    public UserResponseDto updateUser(UserUpdateRequestDto userUpdateRequestDto) {
        Optional<User> optionalUser = userRepository.findById(userUpdateRequestDto.id());

        if(optionalUser.isEmpty()) {
            log.error("User not found unable to update");
            throw new UserNotFoundException("User not found unable to update");
        }

        User updatedUser = optionalUser.get();
        updatedUser.setName(userUpdateRequestDto.name());
        updatedUser.setEmail(userUpdateRequestDto.email());
        updatedUser.setAddress(userUpdateRequestDto.address());

        userRepository.save(updatedUser);

        log.info("User updated successfully: name={}, email={}", userUpdateRequestDto.name(), userUpdateRequestDto.email());
        return toResponseDto(updatedUser);
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
