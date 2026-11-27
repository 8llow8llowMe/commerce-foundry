package com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public final class StoreNavigationAdminResponses {
    private StoreNavigationAdminResponses() {
    }

    public record Text(
        @Schema(description = "국문 문구")
        String ko,

        @Schema(description = "영문 문구")
        String en
    ) {
    }

    public record Item(
        @Schema(description = "리비전 안 편집용 항목 key")
        String key,

        @Schema(description = "상위 항목 key (최상위 null)", nullable = true)
        String parentKey,

        @Schema(description = "메뉴 유형 제어 코드", allowableValues = {"INTERNAL", "EXTERNAL", "CATEGORY", "NONE"})
        String type,

        @Schema(description = "국문·영문 표시명")
        Text labels,

        @Schema(description = "국문·영문 한 줄 설명 (없으면 null)", nullable = true)
        Text descriptions,

        @Schema(description = "내부 경로 또는 외부 HTTPS URL (CATEGORY·NONE 은 null)", nullable = true)
        String url,

        @Schema(description = "카테고리 아이디 (CATEGORY 만)", example = "42", nullable = true)
        String categoryId,

        @Schema(description = "같은 상위 항목 안 표시 순서")
        int displayOrder,

        @Schema(description = "공개 노출 여부")
        boolean visible,

        @Schema(description = "PC 헤더 1차 메뉴 노출 (최상위 항목만)")
        boolean primary,

        @Schema(description = "모바일 헤더 바로가기 노출 (최상위 항목만)")
        boolean shortcut
    ) {
    }

    public record Revision(
        @Schema(description = "리비전 번호")
        int revisionNumber,

        @Schema(description = "숨김 포함 전체 항목. 트리 전위 순서(부모 다음 자식, 형제는 표시 순서 오름차순)")
        List<Item> items,

        @Schema(description = "작성 관리자 아이디")
        String createdBy,

        @Schema(description = "작성 시각 (UTC)")
        Instant createdAt,

        @Schema(description = "최초 게시 관리자 아이디 (미게시 null)", nullable = true)
        String publishedBy,

        @Schema(description = "최초 게시 시각 (UTC, 미게시 null)", nullable = true)
        Instant publishedAt
    ) {
    }

    public record Detail(
        @Schema(description = "낙관적 편집 버전")
        Long version,

        @Schema(description = "최신 초안 리비전 번호")
        int latestRevision,

        @Schema(description = "현재 게시 리비전 번호 (미게시 null)", nullable = true)
        Integer publishedRevision,

        @Schema(description = "항상 최신 저장본의 불변 내용과 이력")
        Revision revision
    ) {
    }
}
