package vn.aiot.biochar.modules.tv5.service;

import tools.jackson.databind.JsonNode;
import java.util.*;
import vn.aiot.biochar.modules.tv5.dto.response.ReportDownload;

public interface IReportService {
    Map<String, Object> create(UUID user, JsonNode body);
    ReportDownload download(UUID id);
    Map<String,Object> list(int page, int limit, String type);
    Map<String,Object> options();
    List<Map<String,Object>> kpis();
}
