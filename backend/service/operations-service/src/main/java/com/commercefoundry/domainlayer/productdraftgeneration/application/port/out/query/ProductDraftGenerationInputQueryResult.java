package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query;

import java.time.Instant;
import java.util.List;

/**
 * product 가 고정한 AI 상품 초안 생성 입력 (product {@code GET /internal/v1/product-drafts/{draftId}/generation-input}). 운영자가 입력한 사실만 사실이다.
 * 스토리지 키 · ETag · 공개 주소는 없다 — 바이트는 이미지 순번으로만 읽는다.
 */
public record ProductDraftGenerationInputQueryResult(long draftId, String status, Instant expiresAt, Facts facts, int imageCount, List<Image> images) {

    public ProductDraftGenerationInputQueryResult {
        images = List.copyOf(images);
    }

    /** 운영자 입력 사실. 목록은 비면 빈 목록이다. */
    public record Facts(String nameHint, List<String> materials, List<String> careNotes, String measurementsNote, String origin,
                        List<String> certifications, String tone, List<String> mustInclude, List<String> mustAvoid) {

        public Facts {
            materials = copyOf(materials);
            careNotes = copyOf(careNotes);
            certifications = copyOf(certifications);
            mustInclude = copyOf(mustInclude);
            mustAvoid = copyOf(mustAvoid);
        }

        private static List<String> copyOf(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public String toString() {
            return "Facts[redacted]";
        }
    }

    /** 고정한 이미지 메타데이터. {@code index} 가 이미지 읽기 순번이다. */
    public record Image(int index, String imageType, String contentType, long byteLength, Integer width, Integer height) {
    }

    /** 운영자 입력을 로그로 내지 않는다. */
    @Override
    public String toString() {
        return "ProductDraftGenerationInputQueryResult[redacted]";
    }
}
