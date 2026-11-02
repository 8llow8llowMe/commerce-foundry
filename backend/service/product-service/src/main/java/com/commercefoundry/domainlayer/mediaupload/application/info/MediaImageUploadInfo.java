package com.commercefoundry.domainlayer.mediaupload.application.info;

import java.time.Instant;
import java.util.Map;

public final class MediaImageUploadInfo {
    private MediaImageUploadInfo() {}

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
