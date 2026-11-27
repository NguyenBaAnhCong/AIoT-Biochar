package vn.aiot.biochar.modules.tv5.rest.impl;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import vn.aiot.biochar.modules.tv5.wrapper.ApiResponse;

abstract class RestSupport {
    protected UUID user(HttpServletRequest request) { return (UUID)request.getAttribute("userId"); }
    protected ApiResponse<?> data(Object value) { return ApiResponse.of(value); }
}
