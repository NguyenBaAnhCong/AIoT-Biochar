package vn.aiot.biochar.modules.tv5.dao.impl;

import java.util.*;
import org.springframework.stereotype.Repository;
import vn.aiot.biochar.modules.tv5.dao.ISystemDao;
import vn.aiot.biochar.modules.tv5.dao.jdbc.JdbcDatabase;

@Repository
public class SystemDaoImpl implements ISystemDao {
    private final JdbcDatabase jdbc;
    public SystemDaoImpl(JdbcDatabase jdbc) { this.jdbc = jdbc; }

    @Override
    public Map<String, Object> currentDatabase() {
        return jdbc.one("SELECT current_database() AS name");
    }

    @Override
    public Map<String, Object> activeActor(String username) {
        return jdbc.one("SELECT user_id FROM iam.users WHERE username=$1 AND is_active=true", username);
    }

    @Override
    public Map<String, Object> datasetStatus(UUID datasetId) {
        return jdbc.one("SELECT status FROM dataset.dataset_versions WHERE dataset_version_id=$1", datasetId);
    }

    @Override
    public List<Map<String, Object>> activeDefinitions() {
        return jdbc.rows("SELECT definition_id FROM sqi.sqi_definitions WHERE status='ACTIVE' ORDER BY created_at DESC LIMIT 1");
    }
}
