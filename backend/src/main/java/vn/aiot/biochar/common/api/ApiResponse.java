package vn.aiot.biochar.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, PageMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> page(T data, PageMeta meta) {
        return new ApiResponse<>(data, meta);
    }

    public record PageMeta(int page, int limit, long total) {
    }
}
