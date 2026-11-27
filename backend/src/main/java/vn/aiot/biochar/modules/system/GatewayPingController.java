package vn.aiot.biochar.modules.system;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.modules.auth.ApiKeyPrincipal;

@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayPingController {

    @GetMapping("/ping")
    @PreAuthorize("hasAuthority('SCOPE_iot.ingest')")
    public ApiResponse<Map<String, Object>> ping(@AuthenticationPrincipal ApiKeyPrincipal apiKey) {
        return ApiResponse.of(Map.of(
                "ok", true,
                "apiKeyId", apiKey.id(),
                "prefix", apiKey.prefix(),
                "scopes", apiKey.scopes()));
    }
}
