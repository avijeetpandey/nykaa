package com.avijeet.nykaa.utils.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class ApiResponse<T> {
    @JsonProperty("isError")
    private final boolean isError;

    private final String message;

    private final T data;

    public ApiResponse(boolean isError, String message, T data) {
        this.isError = isError;
        this.data = data;
        this.message = message;
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(false, message, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(true,message,null);
    }
}
