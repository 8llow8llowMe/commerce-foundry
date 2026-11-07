package com.commercefoundry.domainlayer.storefooter.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public final class StoreFooterWebResponses {
    private StoreFooterWebResponses() {
    }

    public record Link(
        @Schema(description = "링크 유형 제어 코드", allowableValues = {"INSTAGRAM", "KAKAO_CHANNEL", "CUSTOM"})
        String type,

        @Schema(description = "요청 언어의 표시명")
        String label,

        @Schema(description = "외부 HTTPS URL")
        String url
    ) {
    }

    public record Content(
        @Schema(description = "요청 언어의 저작권 문구 (null 허용)")
        String copyrightText,

        @Schema(description = "표시 순서 오름차순의 공개 링크만 포함")
        List<Link> links
    ) {
    }
}
