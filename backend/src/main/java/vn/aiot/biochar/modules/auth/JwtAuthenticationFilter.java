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
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserContextService userContextService;
    private final JsonMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtService jwtService, UserContextService userContextService, JsonMapper objectMapper) {
        this.jwtService = jwtService;
        this.userContextService = userContextService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            var userId = jwtService.verifyAccessToken(authorization.substring(7).trim());
            AppUser user = userContextService.getRequired(userId);
            if (user == null || !user.active()) {
                throw AppException.unauthorized("Tài khoản không tồn tại hoặc đã bị khóa");
            }
            var authorities = user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
            var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
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
