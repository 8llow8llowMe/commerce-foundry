package com.commercefoundry.domainlayer.mediaupload.domain.model;

import java.util.UUID;
import java.util.regex.Pattern;

public record MediaImageUploadKey(UUID id, MediaImageFormat format) {
    public MediaImageUploadKey {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(format, "format");
    }
    public static final String BASENAME_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)";
    private static final Pattern BASENAME = Pattern.compile(BASENAME_REGEX);

    public static MediaImageUploadKey parse(String value) {
        if (value == null || !BASENAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid upload key");
        }
        int dot = value.indexOf('.');
        UUID id = UUID.fromString(value.substring(0, dot));
        for (MediaImageFormat format : MediaImageFormat.values()) {
            if (format.getExtension().equals(value.substring(dot + 1))) {
                return new MediaImageUploadKey(id, format);
            }
        }
        throw new IllegalArgumentException("Invalid upload key");
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
        return "MediaImageUploadKey[redacted]";
    }
}
