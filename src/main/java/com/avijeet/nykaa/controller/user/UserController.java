package com.avijeet.nykaa.controller.user;

import com.avijeet.nykaa.constants.ApiConstants;
import com.avijeet.nykaa.dto.user.UserRequestDto;
import com.avijeet.nykaa.dto.user.UserResponseDto;
import com.avijeet.nykaa.service.user.UserService;
import com.avijeet.nykaa.utils.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
public class UserController {
    private final UserService userService;

    @PostMapping("/add")
    public ResponseEntity<ApiResponse<UserResponseDto>> addUser(@Valid @RequestBody UserRequestDto userRequestDto) {
        return  ResponseEntity.ok(ApiResponse.success(ApiConstants.DONE_MESSAGE, userService.addUser(userRequestDto)));
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success(ApiConstants.DONE_MESSAGE, null));
    }
}
