package vn.aiot.biochar.modules.data_quality;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
@RequestMapping("/api/v1/tv4/quality")
public class DataQualityController {

    private final IDataQualityService service;

    public DataQualityController(IDataQualityService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('data_quality.read')")
    public ApiResponse<Map<String, Object>> rules() {
        return ApiResponse.of(service.rules());
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, UUID>> createRule(@RequestBody DataQualityService.RuleRequest request) {
        return ApiResponse.of(Map.of("rule_id", service.createRule(request)));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, Boolean>> updateRule(@PathVariable UUID id,
                                                         @RequestBody DataQualityService.RuleRequest request) {
        service.updateRule(id, request);
        return ApiResponse.of(Map.of("updated", true));
    }

    @PatchMapping("/rules/{id}/active")
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, Boolean>> setRuleActive(@PathVariable UUID id, @RequestBody ActiveRequest request) {
        service.setRuleActive(id, request.active());
        return ApiResponse.of(Map.of("active", request.active()));
    }

    @GetMapping("/issues")
    @PreAuthorize("hasAuthority('data_quality.read')")
    public ApiResponse<?> issues(
            @RequestParam(required = false, name = "issue_type") String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "sensor_id") UUID sensorId,
            @RequestParam(required = false, name = "plot_id") UUID plotId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return paged(service.issues(type, status, sensorId, plotId, from, to, page, limit));
    }

    @PostMapping("/checks/run")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, Object>> runChecks(@AuthenticationPrincipal AppUser user,
                                                        @Valid @RequestBody RunChecksRequest request) {
        UUID id = service.runChecks(request.from(), request.to(), user.id());
        return ApiResponse.of(Map.of("check_id", id, "status", "DONE"));
    }

    @GetMapping("/checks")
    @PreAuthorize("hasAuthority('data_quality.read')")
    public ApiResponse<?> checks(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return paged(service.checks(page, limit));
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('data_quality.read')")
    public ApiResponse<Map<String, Object>> statistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.of(service.statistics(from, to));
    }

    @PostMapping("/issues/{id}/confirm")
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, Boolean>> confirm(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                                       @RequestBody ReasonRequest request) {
        service.confirm(id, request.reason(), user.id());
        return ApiResponse.of(Map.of("updated", true));
    }

    @PostMapping("/issues/{id}/ignore")
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, Boolean>> ignore(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                                     @RequestBody ReasonRequest request) {
        service.ignore(id, request.reason(), user.id());
        return ApiResponse.of(Map.of("updated", true));
    }

    @PostMapping("/issues/{id}/correct")
    @PreAuthorize("hasAuthority('data_quality.write')")
    public ApiResponse<Map<String, Boolean>> correct(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                                      @RequestBody CorrectionRequest request) {
        service.correct(id, request.parameterCode(), request.value(), request.reason(), user.id());
        return ApiResponse.of(Map.of("updated", true));
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('data_quality.read')")
    public ApiResponse<?> audit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return paged(service.audit(from, to, page, limit));
    }

    private ApiResponse<?> paged(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Number> meta = (Map<String, Number>) result.get("meta");
        return ApiResponse.page(rows, new ApiResponse.PageMeta(meta.get("page").intValue(),
                meta.get("limit").intValue(), meta.get("total").longValue()));
    }

    public record ActiveRequest(boolean active) { }
    public record RunChecksRequest(@NotNull OffsetDateTime from, @NotNull OffsetDateTime to) { }
    public record ReasonRequest(@NotBlank String reason) { }
    public record CorrectionRequest(@NotBlank String parameterCode, @NotNull BigDecimal value, @NotBlank String reason) { }
}
