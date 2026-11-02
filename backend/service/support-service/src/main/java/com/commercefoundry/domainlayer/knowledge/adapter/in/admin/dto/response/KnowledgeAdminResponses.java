package com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public final class KnowledgeAdminResponses {

    private KnowledgeAdminResponses() {
    }

    public record Summary(
        @Schema(description = "문서 아이디")
        String documentId,

        @Schema(description = "문서 유형")
        CodeNameDescriptionMetadata type,

        @Schema(description = "언어")
        CodeNameDescriptionMetadata locale,

        @Schema(description = "최신 리비전 번호")
        int latestRevision,

        @Schema(description = "현재 게시 리비전 번호 (없으면 null)")
        Integer publishedRevision,

        @Schema(description = "문서 버전")
        Long documentVersion,

        @Schema(description = "최신 리비전 버전")
        Long revisionVersion,

        @Schema(description = "최신 제목")
        String title,

        @Schema(description = "카테고리")
        CodeNameDescriptionMetadata category,

        @Schema(description = "최신 리비전 상태")
        CodeNameDescriptionMetadata status,

        @Schema(description = "유효 시작 시각")
        Instant validFrom,

        @Schema(description = "유효 종료 시각")
        Instant validTo,

        @Schema(description = "승인 관리자 아이디")
        String approvedBy,

        @Schema(description = "승인 시각")
        Instant approvedAt,

        @Schema(description = "게시 시각")
        Instant publishedAt
    ) {
    }

    public record Detail(
        @Schema(description = "문서 아이디")
        String documentId,

        @Schema(description = "리비전 아이디")
        String revisionId,

        @Schema(description = "문서 유형")
        CodeNameDescriptionMetadata type,

        @Schema(description = "언어")
        CodeNameDescriptionMetadata locale,

        @Schema(description = "최신 리비전 번호")
        int latestRevision,

        @Schema(description = "현재 게시 리비전 번호")
        Integer publishedRevision,

        @Schema(description = "응답 리비전 번호")
        int revisionNumber,

        @Schema(description = "문서 버전")
        Long documentVersion,

        @Schema(description = "리비전 버전")
        Long revisionVersion,

        @Schema(description = "제목")
        String title,

        @Schema(description = "본문")
        String body,

        @Schema(description = "카테고리")
        CodeNameDescriptionMetadata category,

        @Schema(description = "리비전 상태")
        CodeNameDescriptionMetadata status,

        @Schema(description = "유효 시작 시각")
        Instant validFrom,

        @Schema(description = "유효 종료 시각")
        Instant validTo,

        @Schema(description = "승인 관리자 아이디")
        String approvedBy,

        @Schema(description = "승인 시각")
        Instant approvedAt,

        @Schema(description = "게시 시각")
        Instant publishedAt
    ) {
    }
}
