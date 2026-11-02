package com.commercefoundry.storage.client;

import java.time.Instant;

/** 비공개 객체 GET 주소와 만료 시각. URL을 아는 사람은 만료 전까지 읽을 수 있으므로 로그에 남기지 않는다. */
public record PresignedDownload(String downloadUrl, Instant expiresAt) {

    /** 객체 키와 서명을 모두 가린다. */
    @Override
    public String toString() {
        return "PresignedDownload[downloadUrl=****, expiresAt=" + expiresAt + "]";
    }
}
