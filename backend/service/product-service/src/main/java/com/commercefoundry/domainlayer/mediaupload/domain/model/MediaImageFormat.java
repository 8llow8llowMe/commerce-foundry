package com.commercefoundry.domainlayer.mediaupload.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MediaImageFormat {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

    public static final long MAX_BYTES = 10 * 1024 * 1024;
    private final String contentType;
    private final String extension;

    public static Optional<MediaImageFormat> ofContentType(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.contentType.equals(normalized)).findFirst();
    }
}
