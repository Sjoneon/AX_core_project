package com.project.backend.mcp.audit;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tool call records are kept for a fixed period (default 90 days) and deleted daily. */
@Component
public class ToolCallRetention {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ToolCallRetention.class);
    private final ToolCallLogRepository calls;
    private final Duration retention;
    private final Clock clock;
    public ToolCallRetention(ToolCallLogRepository calls,@Value("${homecare.tool-calls.retention-days:90}") int days){
        this.calls=calls;this.retention=Duration.ofDays(days);this.clock=Clock.systemUTC();
    }
    @Scheduled(cron="${homecare.tool-calls.purge-cron:0 30 3 * * *}",zone="Asia/Seoul")
    @Transactional
    public int purge(){
        int deleted=calls.deleteCreatedBefore(Instant.now(clock).minus(retention));
        LOG.info("homecare_tool_call_purge deleted={} retention_days={}",deleted,retention.toDays());
        return deleted;
    }
}
