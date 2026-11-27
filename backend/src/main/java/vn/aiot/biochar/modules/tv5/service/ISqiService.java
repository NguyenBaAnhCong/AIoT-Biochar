package vn.aiot.biochar.modules.tv5.service;

import tools.jackson.databind.JsonNode;
import java.util.*;

public interface ISqiService {
    Map<String, Object> create(UUID user, JsonNode body);
    Map<String, Object> activate(UUID user, UUID definition);
    Map<String, Object> calculate(UUID user, UUID definition, UUID dataset, boolean label);
    List<Map<String,Object>> plots();
    Map<String,Object> configuration();
    List<Map<String,Object>> comparison(UUID id);
    Map<String,Object> detail(UUID id);
    List<Map<String,Object>> scores(vn.aiot.biochar.modules.tv5.dto.request.SqiScoreQuery query);
}
