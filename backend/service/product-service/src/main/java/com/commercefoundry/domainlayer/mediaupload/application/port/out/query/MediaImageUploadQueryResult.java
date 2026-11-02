package com.commercefoundry.domainlayer.mediaupload.application.port.out.query;

import java.time.Instant;
import java.util.Map;

public final class MediaImageUploadQueryResult {
    private MediaImageUploadQueryResult() {}

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
