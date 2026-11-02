package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.port.out.JobResultStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 외부 DELETE는 트랜잭션 밖이다. 없는 키 삭제도 성공이다. */
@Component
@RequiredArgsConstructor
public class JobResultDeleteProcessor {
    private final JobResultStoragePort storage;

    public boolean enabled() { return storage.enabled(); }

    public void delete(String key) { storage.delete(key); }
}
