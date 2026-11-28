package com.commercefoundry.domainlayer.mediaupload.application.exception;

import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;

/** 101~106 은 이미지 API(#508), 107~110 은 영상 API(#796)다. 크기 필수 · 최소(103 · 104)는 종류와 무관해 같이 쓴다. */
public final class MediaUploadValidationMessage {
    public static final String TYPE_REQUIRED = "MEDIA_UPLOAD_101:이미지 형식은 필수입니다.";
    public static final String TYPE_FORMAT = "MEDIA_UPLOAD_102:JPEG, PNG, WebP 이미지만 허용됩니다.";
    public static final String LENGTH_REQUIRED = "MEDIA_UPLOAD_103:파일 크기는 필수입니다.";
    public static final String LENGTH_MIN = "MEDIA_UPLOAD_104:파일 크기는 1바이트 이상이어야 합니다.";
    public static final String LENGTH_MAX = "MEDIA_UPLOAD_105:파일 크기는 10MiB 이하여야 합니다.";
    public static final String KEY_REGEX = MediaUploadKey.IMAGE_BASENAME_REGEX;
    public static final String KEY_FORMAT = "MEDIA_UPLOAD_106:발급받은 이미지 업로드 키 형식이어야 합니다.";
    public static final String VIDEO_TYPE_REQUIRED = "MEDIA_UPLOAD_107:영상 형식은 필수입니다.";
    public static final String VIDEO_TYPE_FORMAT = "MEDIA_UPLOAD_108:MP4 영상만 허용됩니다.";
    public static final String VIDEO_LENGTH_MAX = "MEDIA_UPLOAD_109:파일 크기는 100MiB 이하여야 합니다.";
    public static final String VIDEO_KEY_REGEX = MediaUploadKey.VIDEO_BASENAME_REGEX;
    public static final String VIDEO_KEY_FORMAT = "MEDIA_UPLOAD_110:발급받은 영상 업로드 키 형식이어야 합니다.";

    private MediaUploadValidationMessage() {}
}
