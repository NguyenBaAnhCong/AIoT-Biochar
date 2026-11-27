package vn.aiot.biochar.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ApiErrorResponse(ErrorBody error) {

    public record ErrorBody(
            String code,
            String message,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ErrorDetail> details) {
    }

    public record ErrorDetail(String field, String message) {
    }
}
