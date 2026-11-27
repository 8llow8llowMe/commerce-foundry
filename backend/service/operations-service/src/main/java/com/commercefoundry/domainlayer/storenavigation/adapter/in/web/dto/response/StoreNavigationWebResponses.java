package com.commercefoundry.domainlayer.storenavigation.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public final class StoreNavigationWebResponses {
    private StoreNavigationWebResponses() {
    }

    public record Item(
        @Schema(description = "요청 언어의 표시명")
        String label,

        @Schema(description = "요청 언어의 한 줄 설명 (없으면 null)", nullable = true)
        String description,

        @Schema(description = "메뉴 유형 제어 코드", allowableValues = {"INTERNAL", "EXTERNAL", "CATEGORY", "NONE"})
        String type,

        @Schema(description = "INTERNAL 은 / 로 시작하는 내부 경로, EXTERNAL 은 허용된 HTTPS URL. CATEGORY·NONE 은 null", nullable = true)
        String url,

        @Schema(description = "카테고리 아이디 (CATEGORY 만)", example = "42", nullable = true)
        String categoryId,

        @Schema(description = "PC 헤더 1차 메뉴 노출 (최상위 항목만 true 가능)")
        boolean primary,

        @Schema(description = "모바일 헤더 바로가기 노출 (최상위 항목만 true 가능)")
        boolean shortcut,

        @Schema(description = "공개 하위 메뉴 (표시 순서 오름차순, 없으면 빈 목록)")
        List<Item> children
    ) {
    }

    public record Content(
        @Schema(description = "표시 순서 오름차순의 공개 최상위 메뉴. 숨김 항목과 그 하위는 포함하지 않는다")
        List<Item> items
    ) {
    }
}
