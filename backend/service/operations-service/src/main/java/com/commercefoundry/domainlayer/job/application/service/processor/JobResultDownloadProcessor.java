package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobResultDownloadInfo;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultStoragePort;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import com.commercefoundry.global.properties.JobResultFileProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** HEAD와 서명은 외부 I/O이므로 트랜잭션 밖에서 실행한다. */
@Component
@RequiredArgsConstructor
public class JobResultDownloadProcessor {
    private final JobResultStoragePort storage;
    private final JobResultFileProperties properties;
    private final Clock clock;

    public JobResultDownloadInfo sign(JobResultFile file) {
        var head = storage.head(file.objectKey()).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_RESULT_FILE));
        if (head.sizeBytes() != file.sizeBytes() || !file.eTag().equals(head.eTag()) || !file.contentType().equals(head.contentType())) {
            throw new JobException(JobErrorCode.RESULT_STORAGE_UNAVAILABLE);
        }
        long seconds = Math.min(properties.downloadTtl().toSeconds(), Math.min(storage.maximumTtl().toSeconds(),
            Duration.between(clock.instant(), file.expiresAt()).toSeconds()));
        if (seconds < 1) { throw new JobException(JobErrorCode.RESULT_FILE_EXPIRED); }
        var signed = storage.sign(file.objectKey(), Duration.ofSeconds(seconds));
        if (signed.expiresAt().isAfter(file.expiresAt()) || !clock.instant().isBefore(signed.expiresAt())) {
            throw new JobException(JobErrorCode.RESULT_FILE_EXPIRED);
        }
        return new JobResultDownloadInfo(signed.url(), signed.expiresAt(), file.fileName(), file.contentType(), file.sizeBytes());
    }
}
