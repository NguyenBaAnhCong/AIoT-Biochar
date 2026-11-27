package vn.aiot.biochar.modules.tv5.dao;

import java.util.*;

public interface ISystemDao {
    Map<String, Object> currentDatabase();
    Map<String, Object> activeActor(String username);
    Map<String, Object> datasetStatus(UUID datasetId);
    List<Map<String, Object>> activeDefinitions();
}
