package vn.aiot.biochar.modules.auth;

import java.util.List;
import java.util.UUID;

public record ApiKeyPrincipal(UUID id, String prefix, List<String> scopes, UUID ownerUserId) {

    public ApiKeyPrincipal {
        scopes = List.copyOf(scopes);
    }
}
