package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.model.PreparedJobResultFile;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultStoragePort;
import com.commercefoundry.domainlayer.job.domain.model.JobResultContent;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobResultUploadProcessor {
    private final JobResultStoragePort storage;

    public PreparedJobResultFile upload(JobResultFile file, JobResultContent content) {
        storage.put(file.objectKey(), content);
        var head = storage.head(file.objectKey()).orElseThrow(() -> new IllegalStateException("작업 결과 파일 값이 올바르지 않습니다."));
        if (head.sizeBytes() != file.sizeBytes() || !file.contentType().equals(head.contentType()) || head.eTag() == null || head.eTag().isBlank()) {
            throw new IllegalStateException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        return new PreparedJobResultFile(file.id(), head.eTag());
    }
}
