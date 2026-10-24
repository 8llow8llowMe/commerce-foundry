package com.commercefoundry.domainlayer.job.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 작업 등록 요청. 다른 컨텍스트의 기능 Facade(리포트 내보내기 등)가 만들어 {@code JobRegistrationProcessor#register} 에 넘긴다 —
 * 컨텍스트를 넘는 값은 domain 이어야 해서 이 record 가 domain 에 있다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙 · H8).
 * 종류 형식 · 멱등 키 길이 · payload 크기는 {@link Job} 이 한 번 더 막는다.
 *
 * @param type 등록된 핸들러의 종류 ({@code [A-Z0-9_]{1,64}})
 * @param payloadJson 핸들러 입력 JSON (16KiB 이하). 개인정보 원문 대신 아이디를 싣는다
 * @param dedupKey 멱등 키 (200자 이하). 같은 종류 · 같은 키의 활성 작업이 있으면 새로 만들지 않고 그 작업을 돌려준다. 없으면 null
 * @param requestedByMemberId 등록한 어드민 (access 토큰의 memberId). 시스템 등록이면 null
 * @param priority 클수록 먼저 가져간다. 보통 0
 * @param scheduledAt 이 시각부터 실행한다. null 이면 지금이다
 */
public record JobRequest(String type, String payloadJson, String dedupKey, Long requestedByMemberId, int priority, Instant scheduledAt) {

    public JobRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson 이 비었습니다 — 입력이 없으면 \"{}\" 를 넘깁니다.");
        }
    }

    /** 멱등 키 · 예약 없이 지금 실행하는 작업. */
    public static JobRequest now(String type, String payloadJson, Long requestedByMemberId) {
        return new JobRequest(type, payloadJson, null, requestedByMemberId, 0, null);
    }
}
