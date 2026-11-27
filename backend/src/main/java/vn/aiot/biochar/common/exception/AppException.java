package vn.aiot.biochar.common.exception;

import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail;
import java.util.List;

public class AppException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<ErrorDetail> details;

    public AppException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public AppException(int status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    public static AppException badRequest(String message) {
        return new AppException(400, "VALIDATION_ERROR", message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(401, "UNAUTHORIZED", message);
    }

    public static AppException forbidden(String message) {
        return new AppException(403, "FORBIDDEN", message);
    }

    public static AppException notFound(String message) {
        return new AppException(404, "NOT_FOUND", message);
    }

    public static AppException conflict(String message) {
        return new AppException(409, "CONFLICT", message);
    }
}
