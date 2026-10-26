package com.commercefoundry.ai.client;

import java.util.Arrays;
import java.util.Set;

/**
 * 호출에 붙이는 이미지 한 장. 바이트만 받는다 — URL 은 클라이언트가 직접 가져오지 않는다.
 *
 * <p>사이즈표 이미지처럼 글자가 그려진 그림은 마스킹할 수 없다. 이름 · 연락처 · 주소가 보이는 이미지는 넣지 않는다.
 * 한 장이 {@link #MAX_BYTES} 를 넘거나 형식이 아니면 만들지 못한다. 한 요청의 장수 상한은 {@link #MAX_COUNT} 이고,
 * 그 이상은 {@code AiClient} 가 보내지 않고 {@code INPUT_TOO_LONG} 이다.
 *
 * @param mediaType {@code image/jpeg} · {@code image/png} · {@code image/gif} · {@code image/webp}
 * @param bytes     이미지 바이트. 복사본을 가진다
 */
public record AiImage(String mediaType, byte[] bytes) {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";

    /** API 가 받는 형식. */
    public static final Set<String> MEDIA_TYPES = Set.of(JPEG, PNG, GIF, WEBP);

    /** 한 요청에 실을 수 있는 장수. 사이즈표 이관은 이 안이다. */
    public static final int MAX_COUNT = 8;

    /** 장당 바이트 상한. API 의 5MiB 와 같다. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public AiImage {
        if (mediaType == null || !MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("image mediaType 은 image/jpeg · image/png · image/gif · image/webp 중 하나여야 합니다");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("image 는 1바이트 이상 " + MAX_BYTES + "바이트 이하여야 합니다");
        }
        bytes = bytes.clone();
    }

    /** 바이트 원문을 로그로 내지 않는다. */
    @Override
    public String toString() {
        return "AiImage[mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AiImage image)) {
            return false;
        }
        return mediaType.equals(image.mediaType) && Arrays.equals(bytes, image.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
    }
}
