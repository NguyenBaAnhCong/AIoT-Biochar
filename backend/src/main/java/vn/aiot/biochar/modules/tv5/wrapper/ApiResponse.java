package vn.aiot.biochar.modules.tv5.wrapper;



/** Matches the group API contract; pagination keeps its existing data/meta envelope. */
public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> of(T data) { return new ApiResponse<>(data); }
}
