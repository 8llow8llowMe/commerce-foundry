package com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public final class LegalDocumentAdminResponses {
    private LegalDocumentAdminResponses() {
    }

    public record Summary(
        @Schema(description = "문서 유형 제어 코드", allowableValues = {"TERMS", "PRIVACY"})
        String type,

        @Schema(description = "언어 제어 코드", allowableValues = {"ko", "en"})
        String locale,

        @Schema(description = "최신 리비전 번호")
        int latestRevision,

        @Schema(description = "현재 게시 리비전 번호 (미게시 null)")
        Integer publishedRevision,

        @Schema(description = "낙관적 편집 버전")
        Long version
    ) {
    }

    public record Revision(
        @Schema(description = "리비전 번호")
        int revisionNumber,

        @Schema(description = "불변 제목")
        String title,

        @Schema(description = "불변 본문 (단순 텍스트)")
        String body,

        @Schema(description = "작성 관리자 아이디")
        String createdBy,

        @Schema(description = "작성 시각")
        Instant createdAt,

        @Schema(description = "최초 게시 관리자 아이디 (미게시 null)")
        String publishedBy,

        @Schema(description = "최초 게시 시각 (미게시 null)")
        Instant publishedAt
    ) {
    }

    public record Detail(
        @Schema(description = "문서 정보")
        Summary document,

        @Schema(description = "최신 리비전 내용 (현재 게시본 재요청도 최신 상세 반환)")
        Revision revision
    ) {
    }

    public record Template(
        @Schema(description = "브랜드 비종속 템플릿 식별자")
        String templateId,

        @Schema(description = "템플릿 버전")
        int version,

        @Schema(description = "문서 유형 제어 코드", allowableValues = {"TERMS", "PRIVACY"})
        String type,

        @Schema(description = "언어 제어 코드", allowableValues = {"ko", "en"})
        String locale,

        @Schema(description = "검토용 제목")
        String title,

        @Schema(description = "빈칸과 작성 질문을 포함한 시작 구조")
        String body,

        @Schema(description = "게시 전 필수 확인 항목")
        List<String> checklist,

        @Schema(description = "사용 안내와 검토 한계")
        String guidance
    ) {
    }
}
