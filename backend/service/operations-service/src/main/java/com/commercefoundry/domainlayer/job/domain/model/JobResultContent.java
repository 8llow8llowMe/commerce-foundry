package com.commercefoundry.domainlayer.job.domain.model;

import java.util.Objects;

public record JobResultContent(String fileName, String contentType, byte[] bytes, boolean sensitive) {
    public JobResultContent {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(bytes, "bytes");
        if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") || fileName.contains("..")) {
            throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        if (!contentType.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+") || contentType.length() > 120 || bytes.length == 0) {
            throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "JobResultContent[sizeBytes=" + bytes.length + ", sensitive=" + sensitive + "]";
    }
}
