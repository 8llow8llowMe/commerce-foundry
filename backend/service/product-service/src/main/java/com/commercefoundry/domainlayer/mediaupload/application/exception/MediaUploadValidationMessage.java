package com.commercefoundry.domainlayer.mediaupload.application.exception;

import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;

public final class MediaUploadValidationMessage {
    public static final String TYPE_REQUIRED = "MEDIA_UPLOAD_101:이미지 형식은 필수입니다.";
    public static final String TYPE_FORMAT = "MEDIA_UPLOAD_102:JPEG, PNG, WebP 이미지만 허용됩니다.";
    public static final String LENGTH_REQUIRED = "MEDIA_UPLOAD_103:파일 크기는 필수입니다.";
    public static final String LENGTH_MIN = "MEDIA_UPLOAD_104:파일 크기는 1바이트 이상이어야 합니다.";
    public static final String LENGTH_MAX = "MEDIA_UPLOAD_105:파일 크기는 10MiB 이하여야 합니다.";
    public static final String KEY_REGEX = MediaImageUploadKey.BASENAME_REGEX;
    public static final String KEY_FORMAT = "MEDIA_UPLOAD_106:발급받은 이미지 업로드 키 형식이어야 합니다.";

    private MediaUploadValidationMessage() {}
}
