package com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public final class StoreFooterAdminResponses {
    private StoreFooterAdminResponses() {
    }

    public record Text(
        @Schema(description = "국문 문구")
        String ko,

        @Schema(description = "영문 문구")
        String en
    ) {
    }

    public record Link(
        @Schema(description = "링크 유형 제어 코드", allowableValues = {"INSTAGRAM", "KAKAO_CHANNEL", "CUSTOM"})
        String type,

        @Schema(description = "국문·영문 표시명")
        Text labels,

        @Schema(description = "외부 HTTPS URL")
        String url,

        @Schema(description = "표시 순서")
        int displayOrder,

        @Schema(description = "공개 노출 여부")
        boolean visible
    ) {
    }

    public record Revision(
        @Schema(description = "리비전 번호")
        int revisionNumber,

        @Schema(description = "국문·영문 저작권 문구")
        Text copyrightText,

        @Schema(description = "숨김 포함 전체 링크 목록")
        List<Link> links,

        @Schema(description = "작성 관리자 아이디")
        String createdBy,

        @Schema(description = "작성 시각 (UTC)")
        Instant createdAt,

        @Schema(description = "최초 게시 관리자 아이디 (미게시 null)")
        String publishedBy,

        @Schema(description = "최초 게시 시각 (UTC, 미게시 null)")
        Instant publishedAt
    ) {
    }

    public record Detail(
        @Schema(description = "낙관적 편집 버전")
        Long version,

        @Schema(description = "최신 초안 리비전 번호")
        int latestRevision,

        @Schema(description = "현재 게시 리비전 번호 (미게시 null)")
        Integer publishedRevision,

        @Schema(description = "항상 최신 저장본의 불변 내용과 이력")
        Revision revision
    ) {
    }
}
