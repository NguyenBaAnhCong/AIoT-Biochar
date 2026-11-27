package vn.aiot.biochar.modules.tv5.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.aiot.biochar.modules.auth.AppUser;
import vn.aiot.biochar.modules.tv5.common.exception.ApiException;

/** Authorize TV5 using the principal already verified by the shared JWT filter. */
@Configuration
public class Tv5Permissions implements WebMvcConfigurer, HandlerInterceptor {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/tv5/**")
                .excludePathPatterns("/api/v1/tv5/health");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUser user)) {
            throw new ApiException(401, "Cần đăng nhập bằng tài khoản người dùng.");
        }
        String path = request.getServletPath();
        String domain = path.startsWith("/api/v1/tv5/sqi/") ? "sqi"
                : path.startsWith("/api/v1/tv5/reports") ? "reporting" : "alert";
        String method = request.getMethod();
        String permission = domain + ("GET".equals(method) || "HEAD".equals(method) ? ".read" : ".write");
        if (!user.permissions().contains(permission)) throw new ApiException(403, "Thiếu quyền " + permission);
        request.setAttribute("userId", user.id());
        request.setAttribute("permissions", user.permissions());
        response.setHeader("Cache-Control", "no-store");
        return true;
    }
}
