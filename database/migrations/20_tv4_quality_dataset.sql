-- Mở rộng QC và kiểm soát đóng băng dataset cho TV4.
-- Không sửa các tệp SQL khởi tạo đã được dùng để tạo database.

INSERT INTO data_quality.quality_rules (
    rule_code, name, target, rule_type, window_minutes, severity, is_active, description
) VALUES (
    'MISSING_SAMPLE', 'Thiếu mẫu theo chu kỳ cảm biến', 'SENSOR_READING', 'MISSING', 5,
    'ERROR', true, 'Phát hiện slot lấy mẫu không có dữ liệu sau thời gian chờ.'
)
ON CONFLICT (rule_code) DO NOTHING;

CREATE OR REPLACE FUNCTION data_quality.run_missing_sensor_checks(p_check_id UUID)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    v_from TIMESTAMPTZ;
    v_to TIMESTAMPTZ;
    v_rule_id UUID;
    v_grace INTEGER;
    v_created INTEGER := 0;
BEGIN
    SELECT scope_from, scope_to INTO v_from, v_to
    FROM data_quality.quality_checks
    WHERE check_id = p_check_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Quality check % không tồn tại', p_check_id;
    END IF;

    SELECT rule_id, COALESCE(window_minutes, 0) INTO v_rule_id, v_grace
    FROM data_quality.quality_rules
    WHERE is_active AND target = 'SENSOR_READING' AND rule_type = 'MISSING'
    ORDER BY rule_code
    LIMIT 1;
    IF v_rule_id IS NULL THEN
        RETURN 0;
    END IF;

    -- Nếu dữ liệu đến bù sau lần kiểm tra trước, đánh dấu issue cũ đã được khôi phục.
    UPDATE data_quality.data_issues i
    SET status = 'IGNORED', resolved_at = COALESCE(resolved_at, now()),
        details = COALESCE(details, '{}'::jsonb) || jsonb_build_object('resolved_reason', 'Dữ liệu cảm biến đến bù')
    WHERE i.issue_type = 'MISSING' AND i.rule_id = v_rule_id
      AND i.status IN ('OPEN', 'CONFIRMED')
      AND EXISTS (
          SELECT 1 FROM iot.sensor_readings sr
          WHERE sr.sensor_id = i.sensor_id
            AND sr.measured_at BETWEEN i.observed_at - INTERVAL '60 seconds'
                                   AND i.observed_at + INTERVAL '60 seconds'
      );

    WITH sensor_scope AS (
        SELECT s.sensor_id, n.plot_id,
               GREATEST(n.sampling_interval_s, 60)::INTEGER AS interval_seconds,
               COALESCE(s.installed_at, v_from) AS anchor_at,
               GREATEST(v_from, COALESCE(s.installed_at, v_from)) AS range_from,
               LEAST(v_to, COALESCE(s.removed_at, v_to)) AS range_to
        FROM iot.sensors s
        JOIN iot.sensor_nodes n ON n.node_id = s.node_id
        WHERE s.status <> 'REMOVED' AND (s.installed_at IS NOT NULL OR EXISTS (
              SELECT 1 FROM iot.sensor_readings observed
              WHERE observed.sensor_id=s.sensor_id AND observed.measured_at >= v_from AND observed.measured_at < v_to
        ))
          AND COALESCE(s.removed_at, v_to) > v_from
    ), expected_slots AS (
        SELECT ss.sensor_id, ss.plot_id, ss.interval_seconds, slot.expected_at
        FROM sensor_scope ss
        CROSS JOIN LATERAL generate_series(
            ss.anchor_at + CEIL(EXTRACT(EPOCH FROM (ss.range_from - ss.anchor_at)) / ss.interval_seconds)
                            * ss.interval_seconds * INTERVAL '1 second',
            ss.range_to - INTERVAL '1 microsecond',
            ss.interval_seconds * INTERVAL '1 second'
        ) AS slot(expected_at)
        WHERE ss.range_to > ss.range_from
    ), missing_slots AS (
        SELECT e.*
        FROM expected_slots e
        WHERE e.expected_at < v_to - make_interval(mins => v_grace)
          AND NOT EXISTS (
              SELECT 1 FROM iot.sensor_readings sr
              WHERE sr.sensor_id = e.sensor_id
                AND sr.measured_at BETWEEN e.expected_at - make_interval(secs => LEAST(60, e.interval_seconds / 4))
                                       AND e.expected_at + make_interval(secs => LEAST(60, e.interval_seconds / 4))
          )
          AND NOT EXISTS (
              SELECT 1 FROM data_quality.data_issues old
              WHERE old.issue_type = 'MISSING' AND old.rule_id = v_rule_id
                AND old.sensor_id = e.sensor_id AND old.observed_at = e.expected_at
          )
    )
    INSERT INTO data_quality.data_issues (
        check_id, rule_id, issue_type, source_table, source_record_id, sensor_id,
        plot_id, observed_at, details, severity
    )
    SELECT p_check_id, v_rule_id, 'MISSING', 'iot.sensor_readings',
           sensor_id::text || '@' || to_char(expected_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           sensor_id, plot_id, expected_at,
           jsonb_build_object('expected_interval_seconds', interval_seconds), 'ERROR'
    FROM missing_slots;

    GET DIAGNOSTICS v_created = ROW_COUNT;
    RETURN v_created;
END;
$$;

-- Tăng cường kiểm tra ở tầng database để mọi caller đều phải build và không rò rỉ nhóm.
ALTER FUNCTION dataset.freeze_version(UUID) RENAME TO freeze_version_before_tv4;

CREATE OR REPLACE FUNCTION dataset.freeze_version(p_version_id UUID)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    v_status VARCHAR(16);
    v_leaking BIGINT;
    v_checksum TEXT;
BEGIN
    SELECT status INTO v_status
    FROM dataset.dataset_versions
    WHERE dataset_version_id = p_version_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Dataset version % không tồn tại', p_version_id;
    END IF;
    IF v_status <> 'BUILT' THEN
        RAISE EXCEPTION 'Chỉ được freeze version đã BUILD; trạng thái hiện tại: %', v_status;
    END IF;

    SELECT leaking_groups INTO v_leaking
    FROM dataset.v_version_readiness
    WHERE dataset_version_id = p_version_id;
    IF COALESCE(v_leaking, 0) > 0 THEN
        RAISE EXCEPTION 'Dataset version % có % nhóm bị leakage', p_version_id, v_leaking;
    END IF;

    v_checksum := dataset.freeze_version_before_tv4(p_version_id);
    RETURN v_checksum;
END;
$$;

-- Bản audit correction sẵn có chưa lưu nhiệt độ đất trong old/new value.
CREATE OR REPLACE FUNCTION audit.log_reading_correction()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.quality_status = 'CORRECTED' AND OLD.quality_status IS DISTINCT FROM 'CORRECTED' THEN
        INSERT INTO data_quality.data_audit (
            source_table, record_id, action, reason, old_value, new_value, actor_id, actor_type
        ) VALUES (
            'iot.sensor_readings', NEW.reading_id::text, 'CLEAN',
            COALESCE(NULLIF(current_setting('app.change_reason', true), ''), 'Sửa dữ liệu thủ công'),
            jsonb_build_object('ph', OLD.ph, 'ec', OLD.ec, 'soil_moisture', OLD.soil_moisture,
                               'soil_temperature', OLD.soil_temperature, 'n', OLD.n, 'p', OLD.p,
                               'k', OLD.k, 'quality_status', OLD.quality_status),
            jsonb_build_object('ph', NEW.ph, 'ec', NEW.ec, 'soil_moisture', NEW.soil_moisture,
                               'soil_temperature', NEW.soil_temperature, 'n', NEW.n, 'p', NEW.p,
                               'k', NEW.k, 'quality_status', NEW.quality_status),
            NULLIF(current_setting('app.user_id', true), '')::uuid,
            CASE WHEN NULLIF(current_setting('app.user_id', true), '') IS NULL THEN 'SYSTEM' ELSE 'USER' END
        );
    END IF;
    RETURN NULL;
END;
$$;
