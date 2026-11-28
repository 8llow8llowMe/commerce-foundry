package com.commercefoundry.domainlayer.mediaupload.application.info;

import java.time.Instant;
import java.util.Map;

/** 이미지 · 영상 업로드 공용 Info. 서명 URL · 헤더 · 키 · 공개 URL 을 toString 에 싣지 않는다. */
public final class MediaUploadInfo {
    private MediaUploadInfo() {}

    public record Ticket(String uploadKey, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {
        public Ticket {
            headers = Map.copyOf(headers);
        }
        @Override
        public String toString() { return "Ticket[redacted]"; }
    }

    public record Confirmed(String publicUrl, String contentType, long contentLength) {
        @Override
        public String toString() { return "Confirmed[redacted]"; }
    }
}
