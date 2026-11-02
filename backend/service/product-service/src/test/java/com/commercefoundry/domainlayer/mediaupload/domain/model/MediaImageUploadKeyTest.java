package com.commercefoundry.domainlayer.mediaupload.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MediaImageUploadKeyTest {
    @ParameterizedTest
    @ValueSource(strings = {"../a.jpg", "uploads/a.png", "A1111111-1111-1111-1111-111111111111.jpg",
        "1-1-1-1-1.jpg", "11111111-1111-1111-1111-111111111111.svg", "https://cdn.example/a.jpg"})
    void rejectsNonCanonicalBasenames(String value) {
        assertThatThrownBy(() -> MediaImageUploadKey.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalKeyAndMime() {
        var key = MediaImageUploadKey.parse("11111111-1111-1111-1111-111111111111.webp");
        assertThat(key.sourceKey(7)).isEqualTo("uploads/display-media/7/" + key.value());
        assertThat(key.toString()).doesNotContain(key.value());
        assertThat(MediaImageFormat.ofContentType(" IMAGE/WEBP ")).contains(MediaImageFormat.WEBP);
        assertThat(MediaImageFormat.ofContentType("image/svg+xml")).isEmpty();
        assertThat(MediaImageFormat.ofContentType("image/jpeg; charset=utf8")).isEmpty();
    }
}
