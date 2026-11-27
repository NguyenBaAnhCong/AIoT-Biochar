package vn.aiot.biochar.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;
import vn.aiot.biochar.common.api.ApiErrorResponse;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorBody;
import vn.aiot.biochar.modules.auth.AccessLogFilter;
import vn.aiot.biochar.modules.auth.ApiKeyAuthenticationFilter;
import vn.aiot.biochar.modules.auth.JwtAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AccessLogFilter accessLogFilter,
            JwtAuthenticationFilter jwtFilter,
            ApiKeyAuthenticationFilter apiKeyFilter,
            JsonMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/tv5/sqi", "/tv5/alerts", "/tv5/reports", "/tv4/quality", "/tv4/datasets").permitAll()
                        .requestMatchers("/api/v1/health", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                                    new ErrorBody("UNAUTHORIZED", "Chưa đăng nhập hoặc phiên đã hết hạn", List.of())));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                                    new ErrorBody("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này", List.of())));
                        }))
                .addFilterBefore(accessLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyFilter, AccessLogFilter.class)
                .addFilterAfter(jwtFilter, ApiKeyAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(AccessLogFilter filter) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
