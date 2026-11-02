package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

public final class MediaImageUploadResponse {
    private MediaImageUploadResponse() {}

    @Schema(description = "브라우저 PUT 업로드 정보. URL · 헤더를 로그에 남기지 않는다.")
    public record Ticket(
        @Schema(description = "확정할 업로드 basename") String uploadKey,
        @Schema(description = "서명 업로드 주소") String uploadUrl,
        @Schema(description = "HTTP 메서드") String method,
        @Schema(description = "PUT에 그대로 보낼 서명 헤더") Map<String, String> headers,
        @Schema(description = "만료 시각") Instant expiresAt
    ) {
        public Ticket { headers = Map.copyOf(headers); }
        @Override
        public String toString() { return "Ticket[redacted]"; }
    }

    @Schema(description = "전시 · 룩 저장 요청에 넣을 공개 이미지")
    public record Confirmed(
        @Schema(description = "공개 이미지 URL") String publicUrl,
        @Schema(description = "검증된 MIME") String contentType,
        @Schema(description = "검증된 바이트 수") long contentLength
    ) {
        @Override
        public String toString() { return "Confirmed[redacted]"; }
    }
}
