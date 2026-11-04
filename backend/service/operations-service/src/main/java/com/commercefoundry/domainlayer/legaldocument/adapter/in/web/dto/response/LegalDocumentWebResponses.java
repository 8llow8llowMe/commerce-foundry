package com.commercefoundry.domainlayer.legaldocument.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public final class LegalDocumentWebResponses {
    private LegalDocumentWebResponses() {
    }

    public record Summary(
        @Schema(description = "문서 유형 제어 코드", allowableValues = {"TERMS", "PRIVACY"})
        String type,

        @Schema(description = "언어 제어 코드", allowableValues = {"ko", "en"})
        String locale,

        @Schema(description = "현재 게시 리비전 번호")
        int revisionNumber,

        @Schema(description = "게시 제목")
        String title,

        @Schema(description = "최초 게시 시각")
        Instant publishedAt
    ) {
    }

    public record Document(
        @Schema(description = "문서 유형 제어 코드", allowableValues = {"TERMS", "PRIVACY"})
        String type,

        @Schema(description = "언어 제어 코드", allowableValues = {"ko", "en"})
        String locale,

        @Schema(description = "실제 게시한 리비전 번호")
        int revisionNumber,

        @Schema(description = "게시 제목")
        String title,

        @Schema(description = "게시 본문 (단순 텍스트로 표시)")
        String body,

        @Schema(description = "최초 게시 시각")
        Instant publishedAt
    ) {
    }
}
