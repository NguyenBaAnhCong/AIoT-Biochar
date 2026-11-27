package vn.aiot.biochar.modules.auth;

import java.util.List;
import java.util.UUID;

public record AppUser(
        UUID id,
        String username,
        String email,
        String fullName,
        boolean active,
        UUID organizationId,
        List<String> roles,
        List<String> permissions) {

    public AppUser {
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }
}
