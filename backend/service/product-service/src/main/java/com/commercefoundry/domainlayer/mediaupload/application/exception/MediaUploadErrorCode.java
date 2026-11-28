package com.commercefoundry.domainlayer.mediaupload.application.exception;

import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 미디어 업로드 오류. 001~007 은 이미지 API(#508) 계약 그대로다. 영상 API(#796)는 문구가 종류를 말하는 코드를 008~012 로 따로 두고,
 * 종류와 무관한 {@link #INVALID_KEY} · {@link #CHANGED} 는 같이 쓴다. 종류별 선택은 {@link #notFound} 등 정적 메서드로 한다.
 */
@Getter
@RequiredArgsConstructor
public enum MediaUploadErrorCode {
    NOT_FOUND("MEDIA_UPLOAD_001", "업로드 이미지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_KEY("MEDIA_UPLOAD_002", "업로드 키 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    FORMAT("MEDIA_UPLOAD_003", "JPEG, PNG, WebP 이미지만 허용됩니다.", HttpStatus.BAD_REQUEST),
    SIZE("MEDIA_UPLOAD_004", "이미지는 1바이트 이상 10MiB 이하여야 합니다.", HttpStatus.BAD_REQUEST),
    CHANGED("MEDIA_UPLOAD_005", "업로드가 변경되었습니다. 다시 확인해 주세요.", HttpStatus.CONFLICT),
    DISABLED("MEDIA_UPLOAD_006", "이미지 업로드를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    STORAGE("MEDIA_UPLOAD_007", "이미지 저장소를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    VIDEO_NOT_FOUND("MEDIA_UPLOAD_008", "업로드 영상을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    VIDEO_FORMAT("MEDIA_UPLOAD_009", "MP4 영상만 허용됩니다.", HttpStatus.BAD_REQUEST),
    VIDEO_SIZE("MEDIA_UPLOAD_010", "영상은 1바이트 이상 100MiB 이하여야 합니다.", HttpStatus.BAD_REQUEST),
    VIDEO_DISABLED("MEDIA_UPLOAD_011", "영상 업로드를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    VIDEO_STORAGE("MEDIA_UPLOAD_012", "영상 저장소를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    public static MediaUploadErrorCode notFound(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> NOT_FOUND;
            case VIDEO -> VIDEO_NOT_FOUND;
        };
    }

    public static MediaUploadErrorCode format(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> FORMAT;
            case VIDEO -> VIDEO_FORMAT;
        };
    }

    public static MediaUploadErrorCode size(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> SIZE;
            case VIDEO -> VIDEO_SIZE;
        };
    }

    public static MediaUploadErrorCode disabled(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> DISABLED;
            case VIDEO -> VIDEO_DISABLED;
        };
    }

    public static MediaUploadErrorCode storage(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> STORAGE;
            case VIDEO -> VIDEO_STORAGE;
        };
    }
}
