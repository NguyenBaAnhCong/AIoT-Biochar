package vn.aiot.biochar.modules.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import vn.aiot.biochar.common.api.ApiErrorResponse;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorBody;
import vn.aiot.biochar.common.exception.AppException;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final JsonMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, JsonMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawKey = request.getHeader("X-API-Key");
        if (rawKey == null || rawKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            ApiKeyPrincipal principal = apiKeyService.authenticate(rawKey.trim());
            var authorities = principal.scopes().stream()
                    .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
            filterChain.doFilter(request, response);
        } catch (AppException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(exception.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                    new ErrorBody(exception.code(), exception.getMessage(), exception.details())));
        }
    }
}
