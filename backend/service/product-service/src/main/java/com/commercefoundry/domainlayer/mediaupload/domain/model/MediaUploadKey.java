package com.commercefoundry.domainlayer.mediaupload.domain.model;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 클라이언트에 돌려주는 업로드 키 — lowercase canonical UUID + 종류별 확장자 basename 이다.
 * 원본 경로는 서버가 JWT actorId 로 재구성한다({@link #sourceKey}). 이미지 · 영상이 같은 원본 prefix 를 쓰지만 UUID 가 발급마다 새로 만들어지고
 * 각 API 가 자기 종류의 확장자만 받으므로({@link #parse}) 한 API 로 다른 종류의 원본을 확정할 수 없다.
 */
public record MediaUploadKey(UUID id, MediaFormat format) {
    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final String IMAGE_BASENAME_REGEX = UUID_REGEX + "\\.(jpg|png|webp)";
    public static final String VIDEO_BASENAME_REGEX = UUID_REGEX + "\\.mp4";
    private static final Pattern IMAGE_BASENAME = Pattern.compile(IMAGE_BASENAME_REGEX);
    private static final Pattern VIDEO_BASENAME = Pattern.compile(VIDEO_BASENAME_REGEX);

    public MediaUploadKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
    }

    /** @throws IllegalArgumentException 종류의 basename 모양이 아니다 — 다른 종류의 확장자도 여기서 거절한다 */
    public static MediaUploadKey parse(MediaKind kind, String value) {
        Pattern basename = switch (kind) {
            case IMAGE -> IMAGE_BASENAME;
            case VIDEO -> VIDEO_BASENAME;
        };
        if (value == null || !basename.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid upload key");
        }
        int dot = value.indexOf('.');
        UUID id = UUID.fromString(value.substring(0, dot));
        return MediaFormat.ofExtension(kind, value.substring(dot + 1))
            .map(format -> new MediaUploadKey(id, format))
            .orElseThrow(() -> new IllegalArgumentException("Invalid upload key"));
    }

    public MediaKind kind() {
        return format.getKind();
    }

    public String value() {
        return id + "." + format.getExtension();
    }

    public String sourceKey(long actorId) {
        if (actorId <= 0) {
            throw new IllegalArgumentException("Invalid actor");
        }
        return "uploads/display-media/" + actorId + "/" + value();
    }

    @Override
    public String toString() {
        return "MediaUploadKey[redacted]";
    }
}
