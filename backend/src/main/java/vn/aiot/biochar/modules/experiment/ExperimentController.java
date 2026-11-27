package vn.aiot.biochar.modules.experiment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.crud.CrudService;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
@RequestMapping("/api/v1")
public class ExperimentController {

    private static final String LIST =
            "/{resource:experiments|treatments|treatment-applications|growth-stages|plot-growth-stages}";
    private static final String ITEM = LIST + "/{id}";

    private final ExperimentService service;
    private final CrudService crud;

    public ExperimentController(ExperimentService service, CrudService crud) {
        this.service = service;
        this.crud = crud;
    }

    @GetMapping(LIST)
    @PreAuthorize("hasAuthority('experiment.read')")
    public ApiResponse<List<Map<String, Object>>> list(
            @PathVariable String resource, @RequestParam Map<String, String> query) {
        return crud.list(service.resource(resource), query);
    }

    @GetMapping(ITEM)
    @PreAuthorize("hasAuthority('experiment.read')")
    public ApiResponse<Map<String, Object>> get(@PathVariable String resource, @PathVariable UUID id) {
        return ApiResponse.of(crud.get(service.resource(resource), id));
    }

    @PostMapping(LIST)
    @PreAuthorize("hasAuthority('experiment.write')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable String resource,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(crud.create(service.resource(resource), body, actor)));
    }

    @PatchMapping(ITEM)
    @PreAuthorize("hasAuthority('experiment.write')")
    public ApiResponse<Map<String, Object>> update(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable String resource,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.of(crud.update(service.resource(resource), id, body, actor));
    }

    @DeleteMapping(ITEM)
    @PreAuthorize("hasAuthority('experiment.write')")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUser actor, @PathVariable String resource, @PathVariable UUID id) {
        crud.delete(service.resource(resource), id, actor);
        return ResponseEntity.noContent().build();
    }
}
