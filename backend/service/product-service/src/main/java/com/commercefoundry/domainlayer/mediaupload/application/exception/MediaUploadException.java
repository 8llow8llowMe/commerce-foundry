package com.commercefoundry.domainlayer.mediaupload.application.exception;

import lombok.Getter;

@Getter
public class MediaUploadException extends RuntimeException {
    private final MediaUploadErrorCode errorCode;

    public MediaUploadException(MediaUploadErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public MediaUploadException(MediaUploadErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public MediaUploadException(MediaUploadErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
