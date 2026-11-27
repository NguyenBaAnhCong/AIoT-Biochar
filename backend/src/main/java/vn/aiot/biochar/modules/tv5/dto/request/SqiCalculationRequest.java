package vn.aiot.biochar.modules.tv5.dto.request;

import tools.jackson.databind.JsonNode;
import java.util.UUID;
import vn.aiot.biochar.modules.tv5.common.exception.ApiException;
import vn.aiot.biochar.modules.tv5.common.util.Values;

public record SqiCalculationRequest(UUID definitionId, UUID datasetVersionId, boolean assignLabels) {
    public static SqiCalculationRequest parse(JsonNode body) {
        if (body.has("label") && !body.path("label").isBoolean()) {
            throw new ApiException(400, "Cờ gán nhãn không hợp lệ.");
        }
        return new SqiCalculationRequest(Values.id(body.path("definition").asText()),
            Values.id(body.path("dataset").asText()), body.path("label").asBoolean(false));
    }
}
