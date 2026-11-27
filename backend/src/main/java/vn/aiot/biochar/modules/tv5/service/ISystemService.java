package vn.aiot.biochar.modules.tv5.service;

import java.util.*;

public interface ISystemService {
    Map<String,Object> health();
    Map<String,Object> activeActor(String name);
    Map<String,Object> datasetStatus(UUID id);
    List<Map<String,Object>> activeDefinitions();
}
