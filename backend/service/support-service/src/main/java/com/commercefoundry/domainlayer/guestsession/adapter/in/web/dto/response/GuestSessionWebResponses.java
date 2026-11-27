package com.commercefoundry.domainlayer.guestsession.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public final class GuestSessionWebResponses {
    private GuestSessionWebResponses() { }

    /** 세션 아이디는 소유 식별자라 내리지 않는다. 토큰은 toString·로그에 남기지 않는다. */
    public record Issued(
        @Schema(description = "비회원 상담 자격 증명(base64url 무패딩 43자). 이 응답에서만 한 번 받는다")
        String token,

        @Schema(description = "자격 증명 만료 시각(UTC ISO-8601), 발급 시각 + 7일. 사용해도 연장되지 않는다", example = "2026-12-04T09:30:00Z")
        Instant expiresAt
    ) {
        @Override
        public String toString() {
            return "Issued[token=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }
}
