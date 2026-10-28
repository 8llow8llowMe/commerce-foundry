package com.commercefoundry.domainlayer.job.application.command;

import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import java.time.Instant;
import java.util.Objects;

/**
 * 작업 등록 — {@code JobRegisterUseCase} 의 입력. 같은 job 컨텍스트의 진입(어드민 재등록, #212 PR-C)이 쓴다.
 * 정기 플래너는 같은 컨텍스트의 Processor 라 {@code JobRegistrationProcessor} 를 바로 부르고, 다른 컨텍스트의 기능 Facade 는 이 커맨드가 아니라 domain {@link JobRequest} 로 {@code JobRegistrationProcessor} 를 부른다(H8).
 *
 * @param type 등록된 핸들러의 종류 ({@code [A-Z0-9_]{1,64}})
 * @param payloadJson 핸들러 입력 JSON (16KiB 이하). 개인정보 원문 대신 아이디를 싣는다
 * @param dedupKey 멱등 키 (200자 이하). 없으면 null
 * @param requestedByMemberId 등록한 어드민. 시스템 등록이면 null
 * @param priority 클수록 먼저 가져간다. 보통 0
 * @param scheduledAt 이 시각부터 실행한다. null 이면 지금이다
 */
public record JobRegisterCommand(String type, String payloadJson, String dedupKey, Long requestedByMemberId, int priority, Instant scheduledAt) {

    public JobRegisterCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }

    /** 멱등 키 · 예약 없이 지금 실행하는 작업. */
    public static JobRegisterCommand now(String type, String payloadJson, Long requestedByMemberId) {
        return new JobRegisterCommand(type, payloadJson, null, requestedByMemberId, 0, null);
    }

    /** @throws IllegalArgumentException payload 가 비었다 */
    public JobRequest toRequest() {
        return new JobRequest(type, payloadJson, dedupKey, requestedByMemberId, priority, scheduledAt);
    }
}
