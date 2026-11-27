package vn.aiot.biochar.common.api;

import java.util.List;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorBody;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail;
import vn.aiot.biochar.common.exception.AppException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleAppException(AppException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.details());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication == null
                || new AuthenticationTrustResolverImpl().isAnonymous(authentication);
        return anonymous
                ? response(401, "UNAUTHORIZED", "Chưa đăng nhập hoặc phiên đã hết hạn", List.of())
                : response(403, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(400, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return response(400, "VALIDATION_ERROR", "JSON gửi lên không hợp lệ", List.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> handleRequestParameter(Exception exception) {
        return response(400, "VALIDATION_ERROR", "Tham số yêu cầu không hợp lệ", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabaseConstraint(DataIntegrityViolationException exception) {
        String sqlState = findSqlState(exception);
        if ("23505".equals(sqlState)) {
            return response(409, "CONFLICT", "Dữ liệu bị trùng", List.of());
        }
        if ("23503".equals(sqlState)) {
            return response(409, "FK_VIOLATION", "Dữ liệu tham chiếu không tồn tại hoặc đang được sử dụng", List.of());
        }
        if ("23502".equals(sqlState) || "23514".equals(sqlState) || "22P02".equals(sqlState)) {
            return response(400, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", List.of());
        }
        log.error("Lỗi ràng buộc cơ sở dữ liệu", exception);
        return response(500, "INTERNAL_ERROR", "Lỗi hệ thống", List.of());
    }

    @ExceptionHandler(BadSqlGrammarException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabaseQuery(BadSqlGrammarException exception) {
        log.error("Câu truy vấn cơ sở dữ liệu không hợp lệ", exception);
        return response(500, "INTERNAL_ERROR", "Lỗi hệ thống", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Lỗi không được xử lý", exception);
        return response(500, "INTERNAL_ERROR", "Lỗi hệ thống", List.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            int status, String code, String message, List<ErrorDetail> details) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ErrorBody(code, message, details)));
    }

    private String findSqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
