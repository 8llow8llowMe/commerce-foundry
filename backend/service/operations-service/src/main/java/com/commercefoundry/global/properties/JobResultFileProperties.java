package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("operations.jobs.result-file")
public record JobResultFileProperties(
    @DefaultValue("10485760") int maxBytes,
    @DefaultValue("7d") Duration retention,
    @DefaultValue("1h") Duration sensitiveRetention,
    @DefaultValue("60s") Duration downloadTtl,
    @DefaultValue("10m") Duration stagingTimeout,
    @DefaultValue("1h") Duration deleteRepeat,
    @DefaultValue("100") int batchSize,
    @DefaultValue("10") int maxBatches,
    @DefaultValue("true") boolean cleanupEnabled
) {
    public JobResultFileProperties {
        if (maxBytes < 1 || maxBytes > 10 * 1024 * 1024 || batchSize < 1 || batchSize > 100 || maxBatches < 1 || maxBatches > 100) {
            throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        for (Duration duration : new Duration[] {retention, sensitiveRetention, downloadTtl, stagingTimeout, deleteRepeat}) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다.");
            }
        }
        if (retention.compareTo(Duration.ofDays(7)) > 0 || sensitiveRetention.compareTo(Duration.ofHours(1)) > 0
            || sensitiveRetention.compareTo(retention) > 0 || downloadTtl.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다.");
        }
    }
}
