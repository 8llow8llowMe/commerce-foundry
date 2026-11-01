package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.application.port.out.query.ProductImageUploadTicket;
import java.time.Instant;
import java.util.Map;

/**
 * 상품 이미지 업로드 자리 (#9 F) — Presenter 에 넘기는 모양. 스토리지 포트 결과({@link ProductImageUploadTicket})를 그대로 옮긴다.
 *
 * @param uploadUrl 서명이 든 주소 — 로그에 남기지 않는다
 */
public record ProductImageUploadInfo(String uploadKey, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {

    public static ProductImageUploadInfo from(ProductImageUploadTicket ticket) {
        return new ProductImageUploadInfo(ticket.uploadKey(), ticket.uploadUrl(), ticket.method(), ticket.headers(), ticket.expiresAt());
    }

    /** 서명 주소는 싣지 않는다. */
    @Override
    public String toString() {
        return "ProductImageUploadInfo[uploadKey=" + uploadKey + ", method=" + method + ", expiresAt=" + expiresAt + "]";
    }
}
