package vn.aiot.biochar.modules.data_quality;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.aiot.biochar.modules.tv5.common.util.Values;
import vn.aiot.biochar.modules.tv5.config.Settings;
import vn.aiot.biochar.modules.tv5.service.ISystemService;

@Component
public class DataQualityJobs {

    private static final Logger log = LoggerFactory.getLogger(DataQualityJobs.class);
    private final Settings settings;
    private final IDataQualityService quality;
    private final ISystemService system;

    public DataQualityJobs(Settings settings, IDataQualityService quality, ISystemService system) {
        this.settings = settings;
        this.quality = quality;
        this.system = system;
    }

    @Scheduled(fixedDelayString = "${TV4_QC_INTERVAL_MS:900000}", initialDelay = 30000)
    public synchronized void run() {
        if (!"true".equalsIgnoreCase(settings.get("TV4_QC_ENABLED", "false"))) return;
        try {
            UUID actor = Values.id(system.activeActor(settings.get("TV4_USERNAME", "researcher01")).get("user_id").toString());
            int runs = quality.runScheduledChecks(actor, Long.parseLong(settings.get("TV4_QC_LOOKBACK_MINUTES", "30")));
            log.info("Job QC TV4 hoàn tất: số lượt chạy mới={}", runs);
        } catch (Exception error) {
            log.error("Job QC TV4 thất bại", error);
        }
    }
}
