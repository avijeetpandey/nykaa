package com.avijeet.nykaa.controller.user;

import com.avijeet.nykaa.constants.ApiConstants;
import com.avijeet.nykaa.dto.user.UserRequestDto;
import com.avijeet.nykaa.dto.user.UserResponseDto;
import com.avijeet.nykaa.dto.user.UserUpdateRequestDto;
import com.avijeet.nykaa.service.user.UserService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
@Slf4j
public class UserController {
    private final UserService userService;

    @PostMapping("/add")
    public ResponseEntity<ApiResponse<UserResponseDto>> addUser(@Valid @RequestBody UserRequestDto userRequestDto) {
        log.info("POST /api/v1/users/add called with email: {}", userRequestDto.email());
        try {
            UserResponseDto response = userService.addUser(userRequestDto);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(ApiConstants.DONE_MESSAGE, response));
        } catch (Exception e) {
            log.error("Error in POST /api/v1/users/add: {}", e.getMessage(), e);
            throw e;
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        log.info("DELETE /api/v1/users/delete/{} called", id);
        try {
            userService.deleteUser(id);
            return ResponseEntity.ok(ApiResponse.success(ApiConstants.DONE_MESSAGE, null));
        } catch (Exception e) {
            log.error("Error in DELETE /api/v1/users/delete/{}: {}", id, e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<UserResponseDto>> updateUser(@Valid @RequestBody UserUpdateRequestDto userUpdateRequestDto) {
        log.info("POST /api/v1/users/update called for user id: {}", userUpdateRequestDto.id());
        try {
            UserResponseDto response = userService.updateUser(userUpdateRequestDto);
            return ResponseEntity.ok(ApiResponse.success(ApiConstants.DONE_MESSAGE, response));
        } catch (Exception e) {
            log.error("Error in POST /api/v1/users/update for user id {}: {}", userUpdateRequestDto.id(), e.getMessage(), e);
            throw e;
        }
    }
}