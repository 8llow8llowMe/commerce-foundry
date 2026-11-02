package com.commercefoundry.domainlayer.job.application.info;

import java.time.Instant;

public record JobResultDownloadInfo(String url, Instant expiresAt, String fileName, String contentType, long sizeBytes) {
    @Override
    public String toString() { return "JobResultDownloadInfo[expiresAt=" + expiresAt + "]"; }
}
