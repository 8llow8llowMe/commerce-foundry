package com.commercefoundry.domainlayer.job.adapter.out.client.storage;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultStoragePort;
import com.commercefoundry.domainlayer.job.domain.model.JobResultContent;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.properties.StorageProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobResultStorageAdapter implements JobResultStoragePort {
    private final ObjectStorageClient client;
    private final StorageProperties properties;

    @Override
    public void put(String key, JobResultContent content) {
        safely(() -> { client.put(key, content.bytes(), content.contentType(), "private, no-store"); return null; });
    }

    @Override
    public Optional<Metadata> head(String key) {
        return safely(() -> client.head(key).map(value -> new Metadata(value.contentLength(), value.contentType(), value.eTag())));
    }

    @Override
    public Download sign(String key, Duration ttl) {
        return safely(() -> {
            var signed = client.presignGet(key, ttl);
            return new Download(signed.downloadUrl(), signed.expiresAt());
        });
    }

    @Override
    public void delete(String key) {
        safely(() -> { client.delete(key); return null; });
    }

    @Override
    public Duration maximumTtl() {
        return properties.presignTtl();
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    private static <T> T safely(Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            throw new JobException(JobErrorCode.RESULT_STORAGE_UNAVAILABLE);
        }
    }
}
