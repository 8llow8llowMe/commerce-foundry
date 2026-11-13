package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign.dto;

import java.time.Instant;
import java.util.List;

/**
 * product {@code ProductDraftGenerationInputResponse}(#359 PR-2a)의 operations 쪽 사본 — 서비스 간 DTO 는 공유하지 않는다. 숫자 칸은 빠짐을 0 과 구분하려고
 * 박싱 타입이다. 응답에 없는 칸이 생겨도 무시한다(Spring 기본 역직렬화).
 */
public record ProductDraftGenerationInputInternalResponse(String draftId, String status, Instant expiresAt, Facts facts, Integer imageCount, List<Image> images) {

    public record Facts(String nameHint, List<String> materials, List<String> careNotes, String measurementsNote, String origin,
                        List<String> certifications, String tone, List<String> mustInclude, List<String> mustAvoid) {

        @Override
        public String toString() {
            return "Facts[redacted]";
        }
    }

    public record Image(Integer index, String imageType, String contentType, Long byteLength, Integer width, Integer height) {
    }

    /** 운영자 입력을 로그로 내지 않는다. */
    @Override
    public String toString() {
        return "ProductDraftGenerationInputInternalResponse[redacted]";
    }
}
