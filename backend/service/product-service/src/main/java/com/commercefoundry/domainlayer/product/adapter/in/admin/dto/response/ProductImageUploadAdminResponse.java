package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;

/**
 * 상품 이미지 업로드 자리 (#9 F). 브라우저가 {@code uploadUrl} 에 {@code method} · {@code headers} 그대로 파일을 올리고, 상품 저장 본문에 {@code uploadKey} 를 넣는다.
 * 키는 발급한 어드민에게만 쓸 수 있고, 저장하지 않은 업로드는 하루 뒤 지워진다.
 */
@Builder
@Schema(description = "상품 이미지 업로드 자리")
public record ProductImageUploadAdminResponse(

    @Schema(description = "업로드 키 — 상품 저장 본문의 이미지에 넣는다", example = "uploads/100/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg")
    String uploadKey,

    @Schema(description = "업로드 주소(서명 포함) — 이 주소로 파일 본문을 그대로 보낸다. 로그 · 화면에 남기지 않는다")
    String uploadUrl,

    @Schema(description = "업로드 HTTP 메서드", example = "PUT")
    String method,

    @Schema(description = "업로드 때 그대로 보낼 요청 헤더(Content-Type 등) — 서명에 들어가 다르면 스토리지가 거절한다")
    Map<String, String> headers,

    @Schema(description = "이 시각(UTC) 뒤에는 올릴 수 없다 — 다시 발급받는다", example = "2026-11-01T03:05:00Z")
    Instant expiresAt
) {

    /** 서명 주소는 싣지 않는다. */
    @Override
    public String toString() {
        return "ProductImageUploadAdminResponse[uploadKey=" + uploadKey + ", method=" + method + ", expiresAt=" + expiresAt + "]";
    }
}
