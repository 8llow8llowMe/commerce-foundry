package com.commercefoundry.domainlayer.mediaupload.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MediaUploadErrorCode {
    NOT_FOUND("MEDIA_UPLOAD_001", "업로드 이미지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_KEY("MEDIA_UPLOAD_002", "업로드 키 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    FORMAT("MEDIA_UPLOAD_003", "JPEG, PNG, WebP 이미지만 허용됩니다.", HttpStatus.BAD_REQUEST),
    SIZE("MEDIA_UPLOAD_004", "이미지는 1바이트 이상 10MiB 이하여야 합니다.", HttpStatus.BAD_REQUEST),
    CHANGED("MEDIA_UPLOAD_005", "업로드가 변경되었습니다. 다시 확인해 주세요.", HttpStatus.CONFLICT),
    DISABLED("MEDIA_UPLOAD_006", "이미지 업로드를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    STORAGE("MEDIA_UPLOAD_007", "이미지 저장소를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
