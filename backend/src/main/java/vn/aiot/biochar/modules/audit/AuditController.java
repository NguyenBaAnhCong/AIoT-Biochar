package vn.aiot.biochar.modules.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.exception.AppException;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAuthority('audit.read')")
public class AuditController {

    private static final List<String> OPERATIONS = List.of("INSERT", "UPDATE", "DELETE");
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping("/logs")
    public ApiResponse<List<AuditService.AuditLogSummary>> logs(
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String operation,
            @RequestParam(name = "actor_id", required = false) UUID actorId,
            @RequestParam(required = false) String record,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        validatePaging(page, limit);
        validateDateRange(from, to);
        if (schema != null && !schema.matches("^[a-z_]{1,32}$")) {
            throw AppException.badRequest("schema không hợp lệ");
        }
        if (table != null && !table.matches("^[a-z_]{1,64}$")) {
            throw AppException.badRequest("table không hợp lệ");
        }
        if (operation != null && !OPERATIONS.contains(operation)) {
            throw AppException.badRequest("operation không hợp lệ");
        }
        if (record != null && record.length() > 100) {
            throw AppException.badRequest("record tối đa 100 ký tự");
        }

        var result = service.auditLogs(
                schema, table, operation, actorId, record, from, to, page, limit);
        return ApiResponse.page(
                result.rows(), new ApiResponse.PageMeta(result.page(), result.limit(), result.total()));
    }

    @GetMapping("/logs/{id}")
    public ApiResponse<AuditService.AuditLogDetail> log(@PathVariable long id) {
        if (id < 1) {
            throw AppException.badRequest("id không hợp lệ");
        }
        return ApiResponse.of(service.auditLog(id));
    }

    @GetMapping("/access-logs")
    public ApiResponse<List<AuditService.AccessLogView>> accessLogs(
            @RequestParam(required = false) String action,
            @RequestParam(name = "user_id", required = false) UUID userId,
            @RequestParam(name = "api_key_id", required = false) UUID apiKeyId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        validatePaging(page, limit);
        validateDateRange(from, to);
        if (action != null && !action.matches("^[A-Z_]{2,32}$")) {
            throw AppException.badRequest("action không hợp lệ");
        }
        if (status != null && (status < 100 || status > 599)) {
            throw AppException.badRequest("status phải từ 100 đến 599");
        }
        if (method != null && !METHODS.contains(method)) {
            throw AppException.badRequest("method không hợp lệ");
        }

        var result = service.accessLogs(
                action, userId, apiKeyId, status, method, from, to, page, limit);
        return ApiResponse.page(
                result.rows(), new ApiResponse.PageMeta(result.page(), result.limit(), result.total()));
    }

    @GetMapping("/summary")
    public ApiResponse<AuditService.AuditSummary> summary() {
        return ApiResponse.of(service.summary());
    }

    private void validatePaging(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw AppException.badRequest("page phải ≥ 1 và limit phải từ 1 đến 100");
        }
    }

    private void validateDateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw AppException.badRequest("from phải trước to");
        }
    }
}
