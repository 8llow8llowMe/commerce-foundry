package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.global.properties.JobProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 등록된 핸들러 목록 — 빈으로 등록된 {@link JobHandler} 를 종류로 찾는다. 핸들러 옵션의 빈 칸은 {@code operations.jobs.*} 기본값으로 채운다.
 *
 * <p>잘못된 구성은 기동에서 실패한다 — 종류 형식이 틀리거나 겹치는 핸들러, heartbeat 주기의 3배보다 짧은 lease, 인스턴스 전체 상한을 넘는 종류별 동시 상한.
 * 실행 중에 "어느 핸들러가 이 종류를 맡는가" 가 흔들리지 않게 한다.
 */
@Component
public class JobHandlerProcessor {

    private final Map<String, RegisteredJobHandler> handlersByType;

    public JobHandlerProcessor(ObjectProvider<JobHandler> handlers, JobProperties jobProperties) {
        JobHandlerOptions defaults = jobProperties.defaultHandlerOptions();
        Map<String, RegisteredJobHandler> registered = new LinkedHashMap<>();
        handlers.orderedStream().forEach(handler -> {
            String type = handler.type();
            if (type == null || !Job.TYPE_PATTERN.matcher(type).matches()) {
                throw new IllegalStateException("작업 종류는 [A-Z0-9_]{1,64} 이어야 합니다: " + type + " (" + handler.getClass().getName() + ")");
            }
            JobHandlerOptions options = (handler.options() == null ? JobHandlerOptions.defaults() : handler.options()).orDefaults(defaults);
            JobProperties.requireHeartbeatWithinLease(jobProperties.worker().heartbeatInterval(), options.leaseDuration(), type + " 핸들러의 leaseDuration");
            if (options.maxConcurrencyPerInstance() > jobProperties.worker().concurrency()) {
                throw new IllegalStateException(type + " 핸들러의 maxConcurrencyPerInstance 가 operations.jobs.worker.concurrency 를 넘습니다.");
            }
            RegisteredJobHandler previous = registered.putIfAbsent(type, new RegisteredJobHandler(handler, options));
            if (previous != null) {
                throw new IllegalStateException("작업 종류 " + type + " 를 맡는 핸들러가 둘입니다: "
                    + previous.handler().getClass().getName() + ", " + handler.getClass().getName());
            }
        });
        this.handlersByType = Collections.unmodifiableMap(registered);
    }

    public Optional<RegisteredJobHandler> find(String type) {
        return Optional.ofNullable(handlersByType.get(type));
    }

    /** 이 인스턴스가 실행할 수 있는 종류. 핸들러가 없으면 비었다 — 워커는 조회하지 않는다. */
    public Set<String> types() {
        return handlersByType.keySet();
    }

    /**
     * 핸들러와 기본값을 채운 옵션.
     *
     * @param options 모든 칸이 있다
     */
    public record RegisteredJobHandler(JobHandler handler, JobHandlerOptions options) {
    }
}
