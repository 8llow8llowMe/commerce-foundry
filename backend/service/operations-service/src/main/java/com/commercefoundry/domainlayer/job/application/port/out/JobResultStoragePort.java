package com.commercefoundry.domainlayer.job.application.port.out;

import com.commercefoundry.domainlayer.job.domain.model.JobResultContent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface JobResultStoragePort {
    void put(String key, JobResultContent content);
    Optional<Metadata> head(String key);
    Download sign(String key, Duration ttl);
    void delete(String key);
    Duration maximumTtl();
    boolean enabled();

    record Metadata(long sizeBytes, String contentType, String eTag) { }

    record Download(String url, Instant expiresAt) {
        @Override
        public String toString() {
            return "Download[expiresAt=" + expiresAt + "]";
        }
    }
}
