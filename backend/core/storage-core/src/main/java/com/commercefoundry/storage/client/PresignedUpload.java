package com.commercefoundry.storage.client;

import java.time.Instant;
import java.util.Map;

/**
 * 브라우저 직접 업로드에 필요한 것 — 이 주소로 {@code method} 요청을 보내고 {@code headers} 를 그대로 싣는다.
 *
 * @param uploadUrl 서명 URL. 쿼리에 서명이 있어 아는 사람은 만료 전까지 같은 형식 · 길이로 올릴 수 있다 — 로그에 남기지 않는다
 * @param method    {@code PUT}
 * @param headers   요청에 실어야 하는 서명 헤더(지금은 Content-Type). Host · Content-Length 는 HTTP 클라이언트(브라우저)가 스스로 넣고
 *                  직접 넣을 수도 없어 뺀다 — 브라우저는 본문 길이로 Content-Length 를 넣으므로 서명한 길이와 같은 파일이어야 한다
 * @param expiresAt 서명 만료 시각
 */
public record PresignedUpload(String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {

    public static final String METHOD_PUT = "PUT";

    public PresignedUpload {
        headers = Map.copyOf(headers);
    }

    /** 서명(쿼리)이 로그로 새지 않게 경로까지만 보인다. */
    @Override
    public String toString() {
        int query = uploadUrl.indexOf('?');
        String withoutSignature = query < 0 ? uploadUrl : uploadUrl.substring(0, query) + "?****";
        return "PresignedUpload[uploadUrl=" + withoutSignature + ", method=" + method + ", headers=" + headers + ", expiresAt=" + expiresAt + "]";
    }
}
