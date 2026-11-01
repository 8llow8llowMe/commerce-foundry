package com.commercefoundry.domainlayer.product.application.port.out.query;

import java.time.Instant;
import java.util.Map;

/**
 * 상품 이미지 임시 업로드 자리 (#9 F, presigned PUT). 브라우저가 {@code uploadUrl} 에 {@code method} 로 {@code headers} 를 그대로 실어 올리고,
 * 상품 저장 때 {@code uploadKey} 를 본문에 넣는다.
 *
 * @param uploadUrl 서명이 든 주소 — 로그에 남기지 않는다
 * @param headers 서명에 들어간 요청 헤더(Content-Type 등). 그대로 보내야 서명이 맞는다
 * @param expiresAt 이 시각 뒤에는 올릴 수 없다
 */
public record ProductImageUploadTicket(String uploadKey, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {

    public ProductImageUploadTicket {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 서명 주소는 싣지 않는다. */
    @Override
    public String toString() {
        return "ProductImageUploadTicket[uploadKey=" + uploadKey + ", method=" + method + ", expiresAt=" + expiresAt + "]";
    }
}
