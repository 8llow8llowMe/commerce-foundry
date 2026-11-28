package com.commercefoundry.domainlayer.mediaupload.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 업로드 API 가 받는 파일 형식. 정규 MIME 은 서명 · 복사본 메타데이터에, 확장자는 업로드 키 · 공개 키에 쓴다. */
@Getter
@RequiredArgsConstructor
public enum MediaFormat {
    JPEG(MediaKind.IMAGE, "image/jpeg", "jpg"),
    PNG(MediaKind.IMAGE, "image/png", "png"),
    WEBP(MediaKind.IMAGE, "image/webp", "webp"),
    /** ISO BMFF MP4 — 앞부분 ftyp box 를 {@link Mp4Signature} 로 판별한다 (#796). webm 등은 후속이다. */
    MP4(MediaKind.VIDEO, "video/mp4", "mp4");

    private final MediaKind kind;
    private final String contentType;
    private final String extension;

    /**
     * 종류 안에서만 MIME 을 찾는다 — 이미지 API 가 video/mp4 를, 영상 API 가 image/* 를 받지 않게.
     * 앞뒤 공백 제거 · 소문자 정규화 뒤 정확히 같아야 한다. 파라미터({@code ;codecs=…}) · 비표준 별칭은 받지 않는다.
     */
    public static Optional<MediaFormat> ofContentType(MediaKind kind, String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.kind == kind && format.contentType.equals(normalized)).findFirst();
    }

    static Optional<MediaFormat> ofExtension(MediaKind kind, String extension) {
        return Arrays.stream(values()).filter(format -> format.kind == kind && format.extension.equals(extension)).findFirst();
    }
}
