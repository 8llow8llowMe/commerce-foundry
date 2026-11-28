package com.commercefoundry.domainlayer.mediaupload.domain.model;

/**
 * 업로드 API 단위의 미디어 종류. 종류마다 허용 형식({@link MediaFormat#getKind()}) · 크기 상한 · 업로드 키 확장자가 다르다.
 * 이미지 API(#508)는 {@link #IMAGE}, 영상 API(#796)는 {@link #VIDEO} 만 받는다 — 한 API 가 다른 종류의 형식 · 키를 받지 않는다.
 */
public enum MediaKind {
    IMAGE, VIDEO;

    /** 이미지 파일 상한 10MiB (#508 계약). 요청 DTO 의 {@code @Max} 가 쓰도록 상수다. */
    public static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    /** 영상 파일 상한 100MiB (#796 사용자 결정). 요청 DTO 의 {@code @Max} 가 쓰도록 상수다. */
    public static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024;

    public long maxBytes() {
        return switch (this) {
            case IMAGE -> IMAGE_MAX_BYTES;
            case VIDEO -> VIDEO_MAX_BYTES;
        };
    }

    /** 1바이트 이상 상한 이하. 발급 요청의 contentLength 와 확정 때 HEAD 실제 크기에 같은 규칙을 쓴다. */
    public boolean allowsSize(long bytes) {
        return bytes >= 1 && bytes <= maxBytes();
    }
}
